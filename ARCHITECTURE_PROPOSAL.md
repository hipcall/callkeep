# CallKeep Architecture Proposal: Reliability-First Rewrite

## Executive Summary

This document proposes a comprehensive architectural rewrite of the CallKeep Flutter plugin, focusing on **reliability, consistency, and real-time state synchronization**. The rewrite maintains 100% backward compatibility (drop-in replacement) while addressing critical architectural flaws that impact reliability when coordinating with SIP agents, call screens, and 3rd party APIs.

### Key Goals
1. **Reliability**: Predictable state management, comprehensive error handling, robust synchronization
2. **Backward Compatibility**: Drop-in replacement with existing public API unchanged
3. **Real-time Sync**: Single source of truth for call state, observable streams for coordinating multiple entities
4. **Maintainability**: Testable code, clear separation of concerns, reduced coupling

---

## Current Architecture Analysis

### Critical Issues Identified

#### 1. State Management Problems
**Current State**: Scattered across multiple locations
- iOS: Static variables in `CallKeep.m`, `NSUserDefaults`, `CXCallController.callObserver`
- Android: Static variables in `CallKeepModule`, `ConcurrentHashMap` in `VoiceConnectionService`, `SharedPreferences`
- No single source of truth
- State synchronization issues when coordinating with SIP agents

**Impact on Reliability**:
- Race conditions when multiple entities (SIP, UI, native OS) update state
- Difficult to debug state inconsistencies
- No validation of state transitions (can end up in invalid states)

#### 2. Error Handling Architecture Missing
**Current State**: Errors logged but not propagated to Flutter
- iOS: `CXTransaction` errors logged, not returned to caller
- Android: Many void methods, silent failures
- No structured error types
- VoIP push failures can cause app termination (recent revert of fix)

**Impact on Reliability**:
- Apps can't handle failures gracefully
- Silent failures lead to stuck states
- No way to recover from platform errors

#### 3. God Object Anti-Pattern
**Current State**:
- `CallKeepModule.java`: 999 lines, handles 25+ methods
- `CallKeep.m`: 1036 lines, handles setup, calls, audio, push
- Tight coupling, static dependencies

**Impact on Reliability**:
- Hard to test (can't inject mocks)
- Changes in one area affect unrelated functionality
- Difficult to reason about behavior

#### 4. Threading Model Unclear
**Current State**:
- iOS: Queue handling not explicit (defaults to main queue)
- Android: Mix of Handler posts, broadcasts, volatile flags
- No documented threading guarantees

**Impact on Reliability**:
- Potential race conditions
- Callbacks may execute on unexpected threads
- Hard to coordinate with SIP agents running on different threads

#### 5. Platform Differences Leak to App Layer
**Current State**:
- iOS-only methods throw exceptions on Android
- Different event sets per platform
- Inconsistent behavior

**Impact on Reliability**:
- Platform-specific bugs in app code
- Difficult to write cross-platform logic
- Fragile when coordinating with cross-platform SIP agents

---

## Proposed Architecture

### Architectural Principles

1. **Clean Architecture**: Separate domain, data, and presentation layers
2. **Single Source of Truth**: Centralized state management with validation
3. **Explicit State Machines**: Only allow valid state transitions
4. **Comprehensive Error Handling**: Result types and error events
5. **Observable State**: Streams for real-time synchronization
6. **Dependency Injection**: Remove static dependencies, enable testing
7. **Thread Safety**: Explicit threading model with documented guarantees
8. **Backward Compatible**: Existing API unchanged, new features opt-in

---

## Layer Architecture

### 1. Domain Layer (Pure Dart - Platform Independent)

**Purpose**: Business logic, state machines, immutable models

```
lib/domain/
├── models/
│   ├── call.dart                 # Immutable call entity
│   ├── call_state.dart           # Enum: idle, ringing, active, held, ended
│   ├── audio_state.dart          # Audio session state
│   ├── call_error.dart           # Typed error hierarchy
│   └── call_config.dart          # Configuration models
├── repositories/
│   └── call_repository.dart      # Abstract interface (DI)
├── state/
│   ├── call_state_machine.dart   # State transition validation
│   └── call_state_store.dart     # Single source of truth
└── usecases/
    ├── start_call.dart           # Use case: start outgoing call
    ├── answer_call.dart          # Use case: answer incoming call
    ├── end_call.dart             # Use case: end active call
    └── hold_call.dart            # Use case: hold/resume call
```

#### Call State Machine

**States**:
```dart
enum CallState {
  idle,       // No call
  dialing,    // Outgoing call connecting
  ringing,    // Incoming call ringing
  active,     // Call in progress
  held,       // Call on hold
  ended,      // Call terminated
}
```

**Valid Transitions**:
```
idle → dialing → active → held → active → ended → idle
       └─────────────────────────────────────────┘
idle → ringing → active → held → active → ended → idle
       └─────────────────────────────────────────┘
```

**Invalid Transitions Blocked**:
- Can't answer a call that's already active
- Can't hold a call that's ringing
- Can't dial when already in a call (unless hold first)

**Benefits**:
- Prevents invalid states that cause crashes
- Clear error messages when invalid transitions attempted
- Easy to test (pure logic)
- Same validation on both iOS and Android

#### Call State Store

**Purpose**: Single source of truth for all call state

```dart
class CallStateStore {
  final _stateController = BehaviorSubject<Map<String, Call>>();
  final _stateMachine = CallStateMachine();

  // Observable stream for real-time sync
  Stream<Map<String, Call>> get callStates => _stateController.stream;

  // Current snapshot (synchronous)
  Map<String, Call> get currentCalls => _stateController.value;

  // State transitions with validation
  Result<Call> transitionCall(String uuid, CallState newState) {
    final currentCall = currentCalls[uuid];
    final validation = _stateMachine.canTransition(
      from: currentCall?.state,
      to: newState
    );

    if (!validation.isValid) {
      return Failure(StateTransitionError(validation.reason));
    }

    // Update state
    final updatedCall = currentCall.copyWith(state: newState);
    _updateCall(uuid, updatedCall);

    return Success(updatedCall);
  }
}
```

**Benefits for Real-time Sync**:
- SIP agents can subscribe to `callStates` stream
- UI screens get automatic updates
- 3rd party API integrations stay in sync
- Replay capability for late subscribers (BehaviorSubject)
- Thread-safe updates

#### Use Cases

**Purpose**: Encapsulate business logic for each operation

```dart
class AnswerCallUseCase {
  final CallRepository _repository;
  final CallStateStore _store;

  Future<Result<Call>> execute(String callUuid) async {
    // Validate state transition
    final validation = _store.transitionCall(callUuid, CallState.active);
    if (validation.isFailure) {
      return validation;
    }

    // Delegate to platform
    final result = await _repository.answerCall(callUuid);

    return result.fold(
      onSuccess: (call) {
        // State already updated optimistically
        return Success(call);
      },
      onError: (error) {
        // Rollback state on failure
        _store.transitionCall(callUuid, CallState.ringing);
        return Failure(error);
      }
    );
  }
}
```

**Benefits**:
- Clear separation of concerns
- Easy to test (mock repository)
- Optimistic updates with rollback
- Consistent error handling

---

### 2. Data Layer (Platform Channel Bridge)

**Purpose**: Platform communication, error mapping, data conversion

```
lib/data/
├── repositories/
│   └── platform_call_repository.dart  # Implements CallRepository
├── datasources/
│   ├── ios_callkit_datasource.dart    # iOS platform channel
│   ├── android_telecom_datasource.dart # Android platform channel
│   └── platform_detector.dart          # Platform detection
├── mappers/
│   ├── call_mapper.dart                # Platform data ↔ Domain Call
│   ├── error_mapper.dart               # Platform errors ↔ Domain errors
│   └── event_mapper.dart               # Platform events ↔ Domain events
└── models/
    └── platform_call_data.dart         # Raw platform data models
```

#### Platform Call Repository

```dart
class PlatformCallRepository implements CallRepository {
  final PlatformCallDatasource _datasource;
  final CallMapper _callMapper;
  final ErrorMapper _errorMapper;

  @override
  Future<Result<Call>> answerCall(String uuid) async {
    try {
      final platformData = await _datasource.answerCall(uuid);
      final call = _callMapper.toDomain(platformData);
      return Success(call);
    } on PlatformException catch (e) {
      final error = _errorMapper.toDomain(e);
      return Failure(error);
    } catch (e) {
      return Failure(UnknownError(e.toString()));
    }
  }
}
```

**Benefits**:
- Platform differences hidden behind interface
- Consistent error handling
- Easy to test (mock datasource)
- Can swap implementations (useful for testing)

#### Error Mapping

**Platform Errors → Domain Errors**:

```dart
sealed class CallError {
  final String message;
  final String? remediation;
  CallError(this.message, [this.remediation]);
}

// iOS-specific
class CallKitError extends CallError {
  final CXErrorCode code;
  CallKitError(this.code, String message, [String? remediation])
    : super(message, remediation);
}

// Android-specific
class TelecomError extends CallError {
  final int disconnectCause;
  TelecomError(this.disconnectCause, String message, [String? remediation])
    : super(message, remediation);
}

// Cross-platform
class PermissionError extends CallError {
  final List<String> missingPermissions;
  PermissionError(this.missingPermissions)
    : super(
        'Missing permissions: ${missingPermissions.join(", ")}',
        'Request permissions using callKeep.setup()'
      );
}

class StateTransitionError extends CallError {
  final CallState from;
  final CallState to;
  StateTransitionError(this.from, this.to)
    : super('Invalid transition from $from to $to');
}

class VoipPushTimeoutError extends CallError {
  VoipPushTimeoutError()
    : super(
        'Failed to report call to CallKit within 1 second',
        'Optimize push payload processing'
      );
}
```

**Benefits**:
- App code can handle specific error types
- Clear remediation guidance
- Platform-specific details preserved when needed
- Type-safe error handling

---

### 3. Presentation Layer (Public API - Backward Compatible)

**Purpose**: Maintain existing API, add new features as opt-in

```
lib/
├── callkeep.dart                      # Export file (unchanged)
└── src/
    ├── api.dart                       # FlutterCallkeep facade
    ├── event.dart                     # EventManager (enhanced)
    ├── actions.dart                   # Event types (unchanged)
    ├── call.dart                      # CallData (unchanged for compat)
    ├── call_state_stream.dart         # NEW: Observable state
    └── result.dart                    # NEW: Result types
```

#### FlutterCallkeep API (Backward Compatible)

```dart
class FlutterCallkeep extends EventManager {
  // Existing methods - UNCHANGED signatures
  Future<void> setup(Map<String, dynamic> options) async {
    // Internally uses new architecture
    await _setupUseCase.execute(CallConfig.fromMap(options));
  }

  Future<void> displayIncomingCall(
    String uuid,
    String handle, {
    String? localizedCallerName,
    // ... existing parameters
  }) async {
    // Internally uses new architecture
    await _displayIncomingCallUseCase.execute(/* ... */);
  }

  // All existing methods maintained for backward compatibility

  // NEW: Observable state stream (opt-in)
  Stream<List<Call>> get callStateStream => _stateStore.callStates
    .map((map) => map.values.toList());

  // NEW: Result-based methods (opt-in, parallel to existing)
  Future<Result<Call>> displayIncomingCallWithResult(/* ... */) async {
    return _displayIncomingCallUseCase.execute(/* ... */);
  }

  // NEW: Unsubscribe support (enhancement)
  @override
  EventSubscription on<T extends EventType>(
    void Function(T event) callback
  ) {
    final subscription = super.on<T>(callback);
    return EventSubscription(() => _unsubscribe<T>(callback));
  }
}
```

#### Migration Strategy

**Phase 1: Drop-in Replacement** (Zero Code Changes)
```dart
// Existing code works identically
final callKeep = FlutterCallkeep();
await callKeep.setup(options);
await callKeep.displayIncomingCall(uuid, handle);

callKeep.on<CallKeepPerformAnswerCallAction>((event) {
  // Existing event handling unchanged
});
```

**Phase 2: Opt-in to New Features** (When Ready)
```dart
// Add state stream for SIP synchronization
callKeep.callStateStream.listen((calls) {
  for (final call in calls) {
    sipAgent.syncCallState(call.uuid, call.state);
    callScreenController.updateCall(call);
    apiClient.reportCallState(call);
  }
});

// Use result types for better error handling
final result = await callKeep.displayIncomingCallWithResult(uuid, handle);
result.fold(
  onSuccess: (call) => print('Call displayed: ${call.uuid}'),
  onError: (error) => handleError(error),
);
```

**Benefits**:
- No breaking changes
- Gradual adoption of new features
- Can mix old and new APIs during migration
- Clear upgrade path

---

## Native Layer Architecture

### iOS Refactoring

**Current**: Single god object (`CallKeep.m` - 1036 lines)

**Proposed**: Service-oriented architecture

```
ios/Classes/
├── Core/
│   ├── CallKitManager.{h,m}           # CXProvider + CXCallController
│   ├── AudioSessionManager.{h,m}      # AVAudioSession management
│   ├── PushKitManager.{h,m}           # PKPushRegistry management
│   └── CallKitProtocols.h             # Shared protocol definitions
├── State/
│   ├── CallStateStore.{h,m}           # Single source of truth
│   ├── CallStateMachine.{h,m}         # State transition validation
│   └── SettingsStore.{h,m}            # Configuration persistence
├── Bridge/
│   ├── FlutterCallKeepBridge.{h,m}    # Flutter ↔ Native coordination
│   ├── CallKeepEventEmitter.{h,m}     # Event emission to Flutter
│   └── CallKeepMethodHandler.{h,m}    # Method call handling
├── Utils/
│   ├── CallKitLogger.{h,m}            # Structured logging
│   └── CallKitValidator.{h,m}         # Input validation
└── FlutterCallkeepPlugin.{h,m}        # Plugin registration (thin)
```

#### CallKitManager (Core Service)

**Responsibilities**:
- Manage `CXProvider` lifecycle
- Execute `CXActions` via `CXCallController`
- Implement `CXProviderDelegate` callbacks
- Report calls to CallKit

**Interface**:
```objc
@protocol CallKitManagerDelegate <NSObject>
- (void)callKitManager:(CallKitManager *)manager
    didChangeCallState:(NSString *)uuid
                 state:(CallState)state;
- (void)callKitManager:(CallKitManager *)manager
         didFailWithError:(NSError *)error;
@end

@interface CallKitManager : NSObject <CXProviderDelegate>

@property (nonatomic, weak) id<CallKitManagerDelegate> delegate;

- (instancetype)initWithConfiguration:(CXProviderConfiguration *)config;
- (void)reportNewIncomingCall:(NSUUID *)uuid
                       handle:(NSString *)handle
            completionHandler:(void(^)(NSError *))completion;
- (void)startOutgoingCall:(NSUUID *)uuid
                   handle:(NSString *)handle
        completionHandler:(void(^)(NSError *))completion;
- (void)endCall:(NSUUID *)uuid
completionHandler:(void(^)(NSError *))completion;

@end
```

**Benefits**:
- Single responsibility (CallKit only)
- Protocol-based (can inject mock for testing)
- Clear error handling via completion blocks
- No static state

#### PushKitManager (VoIP Push Service)

**Current Problem**: VoIP push handling fragile, recent revert of invalid payload fix

**Proposed Solution**: Isolated service with retry logic

```objc
@protocol PushKitManagerDelegate <NSObject>
- (void)pushKitManager:(PushKitManager *)manager
  didReceivePushPayload:(NSDictionary *)payload
                forUUID:(NSUUID *)uuid
      completionHandler:(void(^)(void))completion;
- (void)pushKitManager:(PushKitManager *)manager
didUpdatePushToken:(NSString *)token;
- (void)pushKitManager:(PushKitManager *)manager
didInvalidatePushToken:(void)token;
@end

@interface PushKitManager : NSObject <PKPushRegistryDelegate>

@property (nonatomic, weak) id<PushKitManagerDelegate> delegate;
@property (nonatomic, strong, readonly) NSString *pushToken;

- (void)registerForVoIPPushes;
- (void)invalidate;

@end
```

**Implementation Details**:

```objc
- (void)pushRegistry:(PKPushRegistry *)registry
didReceiveIncomingPushWithPayload:(PKPushPayload *)payload
             forType:(PKPushType)type
withCompletionHandler:(void(^)(void))completion {

  // Start timeout timer (Apple requires < 1 second)
  NSTimer *timeoutTimer = [NSTimer scheduledTimerWithTimeInterval:0.9
    repeats:NO
    block:^(NSTimer *timer) {
      [self.logger logError:@"VoIP push processing exceeded 900ms"];
      [self.eventEmitter emitError:[[VoipPushTimeoutError alloc] init]];
      completion(); // Ensure completion called
    }];

  // Parse payload with validation
  NSError *parseError = nil;
  NSDictionary *parsedPayload = [self parseAndValidatePayload:payload.dictionaryPayload
                                                        error:&parseError];

  if (parseError) {
    [timeoutTimer invalidate];
    [self.logger logError:@"Invalid VoIP push payload: %@", parseError];
    [self.eventEmitter emitError:parseError];
    completion(); // Still call completion to prevent termination
    return;
  }

  // Extract UUID
  NSUUID *callUUID = [self extractCallUUID:parsedPayload];

  // Delegate to handler (reports to CallKit)
  [self.delegate pushKitManager:self
            didReceivePushPayload:parsedPayload
                          forUUID:callUUID
                completionHandler:^{
    [timeoutTimer invalidate];
    completion();
  }];
}

- (NSDictionary *)parseAndValidatePayload:(NSDictionary *)payload
                                    error:(NSError **)error {
  // Validate required fields
  if (!payload[@"uuid"]) {
    if (error) {
      *error = [NSError errorWithDomain:@"CallKeep"
                                   code:1001
                               userInfo:@{@"reason": @"Missing uuid"}];
    }
    return nil;
  }

  if (!payload[@"handle"]) {
    if (error) {
      *error = [NSError errorWithDomain:@"CallKeep"
                                   code:1002
                               userInfo:@{@"reason": @"Missing handle"}];
    }
    return nil;
  }

  return payload;
}
```

**Benefits**:
- Timeout protection (prevents app termination)
- Payload validation with clear errors
- Completion handler always called
- Errors propagated to Flutter (can be handled by app)
- Isolated from CallKit logic (easier to test)

#### CallStateStore (iOS)

**Purpose**: Single source of truth for call state

```objc
@interface CallStateStore : NSObject

- (CallState)stateForCallUUID:(NSUUID *)uuid;
- (NSArray<Call *> *)allCalls;
- (BOOL)transitionCall:(NSUUID *)uuid
               toState:(CallState)newState
                 error:(NSError **)error;
- (void)addObserver:(id<CallStateObserver>)observer;
- (void)removeObserver:(id<CallStateObserver>)observer;

@end

@protocol CallStateObserver <NSObject>
- (void)callStateStore:(CallStateStore *)store
      didUpdateCallState:(Call *)call;
@end
```

**Benefits**:
- Thread-safe (uses serial queue)
- Observable (multiple observers)
- Validates transitions before applying
- Single location for all call state queries

#### Dependency Injection Pattern

**Current**: Static dependencies, hard to test

**Proposed**: Constructor injection

```objc
@interface FlutterCallKeepBridge : NSObject

- (instancetype)initWithCallKitManager:(id<CallKitManaging>)callKitManager
                    audioSessionManager:(id<AudioSessionManaging>)audioManager
                        pushKitManager:(id<PushKitManaging>)pushManager
                          stateStore:(CallStateStore *)stateStore
                        eventEmitter:(CallKeepEventEmitter *)emitter;

@end
```

**Testing**:
```objc
// In unit tests
id<CallKitManaging> mockCallKit = [[MockCallKitManager alloc] init];
id<AudioSessionManaging> mockAudio = [[MockAudioManager alloc] init];
// ... other mocks

FlutterCallKeepBridge *bridge = [[FlutterCallKeepBridge alloc]
  initWithCallKitManager:mockCallKit
      audioSessionManager:mockAudio
          pushKitManager:nil  // Not needed for this test
                stateStore:testStateStore
              eventEmitter:testEmitter];

// Test behavior with mocked dependencies
```

**Benefits**:
- Unit testable
- Clear dependencies
- No static state
- Easy to swap implementations

---

### Android Refactoring

**Current**: God object (`CallKeepModule.java` - 999 lines), static state everywhere

**Proposed**: Service-oriented architecture with dependency injection

```
android/src/main/java/io/wazo/callkeep/
├── core/
│   ├── TelecomManagerWrapper.java     # Android TelecomManager wrapper
│   ├── AudioManagerWrapper.java       # Audio state management
│   ├── PhoneAccountManager.java       # Phone account setup
│   └── CallKeepProtocols.java         # Shared interfaces
├── state/
│   ├── CallStateStore.java            # Single source of truth
│   ├── CallStateMachine.java          # State transition validation
│   └── SettingsStore.java             # Configuration persistence
├── services/
│   ├── VoiceConnectionService.java    # ConnectionService (refactored)
│   ├── WakeLockService.java           # Wake lock management
│   └── ForegroundNotificationService.java # Foreground notification
├── bridge/
│   ├── FlutterCallKeepBridge.java     # Flutter ↔ Native coordination
│   ├── CallKeepEventEmitter.java      # Event emission to Flutter
│   └── CallKeepMethodHandler.java     # Method call handling
├── utils/
│   ├── CallKeepLogger.java            # Structured logging
│   ├── PermissionValidator.java       # Permission checking
│   └── ThreadExecutor.java            # Thread management
└── FlutterCallkeepPlugin.java         # Plugin registration (thin)
```

#### CallStateStore (Android)

**Purpose**: Thread-safe single source of truth

**Current Problem**: `ConcurrentHashMap` in `VoiceConnectionService`, volatile flags scattered

**Proposed**:
```java
public class CallStateStore {
  private final ConcurrentHashMap<String, Call> calls = new ConcurrentHashMap<>();
  private final CallStateMachine stateMachine = new CallStateMachine();
  private final List<CallStateObserver> observers = new CopyOnWriteArrayList<>();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  // Thread-safe state queries
  public synchronized Call getCall(String uuid) {
    return calls.get(uuid);
  }

  public synchronized List<Call> getAllCalls() {
    return new ArrayList<>(calls.values());
  }

  // State transitions with validation
  public synchronized Result<Call> transitionCall(String uuid, CallState newState) {
    Call currentCall = calls.get(uuid);

    // Validate transition
    ValidationResult validation = stateMachine.canTransition(
      currentCall != null ? currentCall.getState() : CallState.IDLE,
      newState
    );

    if (!validation.isValid()) {
      return Result.failure(new StateTransitionError(validation.getReason()));
    }

    // Update state
    Call updatedCall = currentCall.withState(newState);
    calls.put(uuid, updatedCall);

    // Notify observers (on main thread)
    notifyObservers(updatedCall);

    return Result.success(updatedCall);
  }

  private void notifyObservers(Call call) {
    mainHandler.post(() -> {
      for (CallStateObserver observer : observers) {
        observer.onCallStateChanged(call);
      }
    });
  }

  public void addObserver(CallStateObserver observer) {
    observers.add(observer);

    // Replay current state for new observer
    mainHandler.post(() -> {
      for (Call call : getAllCalls()) {
        observer.onCallStateChanged(call);
      }
    });
  }

  public void removeObserver(CallStateObserver observer) {
    observers.remove(observer);
  }
}

public interface CallStateObserver {
  void onCallStateChanged(Call call);
}
```

**Benefits**:
- Thread-safe (synchronized methods)
- Validates transitions
- Observable (multiple observers)
- Replay capability for late subscribers
- Main thread notifications (predictable)

#### VoiceConnectionService (Refactored)

**Current Issues**:
- Static references to `CallKeepModule`
- Complex wake-up mechanism
- Unclear lifecycle

**Proposed**:
```java
public class VoiceConnectionService extends ConnectionService {
  private CallStateStore stateStore;
  private CallKeepEventEmitter eventEmitter;
  private WakeLockService wakeLockService;
  private ForegroundNotificationService notificationService;

  // Dependency injection via service locator (Android limitation)
  @Override
  public void onCreate() {
    super.onCreate();

    ServiceContainer container = ServiceContainer.getInstance(getApplicationContext());
    this.stateStore = container.getCallStateStore();
    this.eventEmitter = container.getEventEmitter();
    this.wakeLockService = container.getWakeLockService();
    this.notificationService = container.getForegroundNotificationService();
  }

  @Override
  public Connection onCreateIncomingConnection(
    PhoneAccountHandle handle,
    ConnectionRequest request
  ) {
    // Extract call info
    String uuid = request.getExtras().getString("uuid");

    // Validate state
    Result<Call> result = stateStore.transitionCall(uuid, CallState.RINGING);
    if (result.isFailure()) {
      Log.e(TAG, "Invalid state transition: " + result.getError());
      return Connection.createFailedConnection(
        new DisconnectCause(DisconnectCause.ERROR, result.getError().getMessage())
      );
    }

    // Create connection
    VoiceConnection connection = new VoiceConnection(
      getApplicationContext(),
      uuid,
      stateStore,
      eventEmitter
    );

    // Start foreground service if first connection
    if (stateStore.getAllCalls().size() == 1) {
      notificationService.startForeground(this);
    }

    return connection;
  }

  @Override
  public void onConnectionRemoved(Connection connection) {
    super.onConnectionRemoved(connection);

    // Stop foreground service if no more connections
    if (stateStore.getAllCalls().isEmpty()) {
      notificationService.stopForeground(this);
    }
  }
}
```

**Benefits**:
- Dependencies injected (testable)
- State validation before creating connections
- Clear lifecycle management
- Errors returned to system (not silent)

#### WakeLockService (Isolated)

**Current Problem**: Complex `CountDownLatch` logic in `CallKeepBackgroundMessagingService`

**Proposed**: Simplified service with automatic timeout

```java
public class WakeLockService {
  private static final long WAKELOCK_TIMEOUT_MS = 60_000; // 60 seconds
  private final Context context;
  private PowerManager.WakeLock wakeLock;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Runnable timeoutRunnable;

  public WakeLockService(Context context) {
    this.context = context.getApplicationContext();
  }

  public synchronized void acquire(String tag) {
    if (wakeLock != null && wakeLock.isHeld()) {
      release(); // Release previous
    }

    PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    wakeLock = powerManager.newWakeLock(
      PowerManager.PARTIAL_WAKE_LOCK,
      "CallKeep::" + tag
    );

    wakeLock.acquire(WAKELOCK_TIMEOUT_MS); // System enforces timeout

    // Additional app-level timeout for cleanup
    scheduleTimeout();

    Log.d(TAG, "Wake lock acquired: " + tag);
  }

  public synchronized void release() {
    cancelTimeout();

    if (wakeLock != null && wakeLock.isHeld()) {
      wakeLock.release();
      Log.d(TAG, "Wake lock released");
    }

    wakeLock = null;
  }

  private void scheduleTimeout() {
    cancelTimeout();

    timeoutRunnable = () -> {
      Log.w(TAG, "Wake lock timeout reached, releasing");
      release();
    };

    handler.postDelayed(timeoutRunnable, WAKELOCK_TIMEOUT_MS);
  }

  private void cancelTimeout() {
    if (timeoutRunnable != null) {
      handler.removeCallbacks(timeoutRunnable);
      timeoutRunnable = null;
    }
  }
}
```

**Benefits**:
- Simplified logic (no CountDownLatch)
- Double timeout protection (system + app)
- Clear acquire/release semantics
- Easy to test

#### Service Locator Pattern (Android DI Alternative)

**Why not Dagger/Hilt**: Adds complexity for a plugin, not all apps use it

**Proposed**: Simple service locator

```java
public class ServiceContainer {
  private static ServiceContainer instance;
  private final Context context;

  // Services (lazy-initialized)
  private CallStateStore callStateStore;
  private CallKeepEventEmitter eventEmitter;
  private WakeLockService wakeLockService;
  private TelecomManagerWrapper telecomManager;

  private ServiceContainer(Context context) {
    this.context = context.getApplicationContext();
  }

  public static synchronized void initialize(Context context) {
    if (instance == null) {
      instance = new ServiceContainer(context);
    }
  }

  public static ServiceContainer getInstance(Context context) {
    if (instance == null) {
      initialize(context);
    }
    return instance;
  }

  public synchronized CallStateStore getCallStateStore() {
    if (callStateStore == null) {
      callStateStore = new CallStateStore();
    }
    return callStateStore;
  }

  // ... other getters with lazy initialization

  // For testing
  public void setCallStateStore(CallStateStore store) {
    this.callStateStore = store;
  }
}
```

**Benefits**:
- Simple dependency management
- Lazy initialization
- Mockable for testing
- No external dependencies
- Clear lifecycle

---

## State Synchronization Strategy

### The Multi-Entity Coordination Problem

**Scenario**: Your Hipcall app needs to coordinate:
1. **SIP Agent**: Manages SIP protocol, signaling
2. **Native OS (CallKit/ConnectionService)**: System call UI
3. **Flutter Call Screen**: In-app call UI
4. **3rd Party API**: Backend call tracking

**Current Problem**: Each entity has its own state, gets out of sync

```
Incoming Call Flow (Current):
┌─────────┐     ┌──────────┐     ┌─────────┐     ┌──────────┐
│   SIP   │────>│ CallKit  │────>│ Flutter │────>│   API    │
└─────────┘     └──────────┘     └─────────┘     └──────────┘
   State 1         State 2          State 3         State 4

Problem: States diverge, difficult to reconcile
```

### Proposed Solution: Observable Single Source of Truth

```
Incoming Call Flow (Proposed):
                    ┌────────────────┐
                    │ CallStateStore │ <- Single Source of Truth
                    │   (Observable) │
                    └───────┬────────┘
                            │
            ┌───────────────┼───────────────┐
            │               │               │
            v               v               v
     ┌──────────┐    ┌──────────┐   ┌──────────┐
     │   SIP    │    │  Flutter │   │   API    │
     │  Agent   │    │   UI     │   │  Client  │
     └──────────┘    └──────────┘   └──────────┘

All entities observe same state, stay in sync
```

### Implementation

#### 1. Set Up Observable State Stream

```dart
class HipcallCallManager {
  final FlutterCallkeep _callKeep;
  final SipAgent _sipAgent;
  final CallScreenController _callScreenController;
  final ApiClient _apiClient;

  StreamSubscription? _stateSubscription;

  void initialize() {
    // Subscribe to call state changes
    _stateSubscription = _callKeep.callStateStream.listen(
      _handleCallStateUpdate,
      onError: _handleCallStateError,
    );
  }

  void _handleCallStateUpdate(List<Call> calls) {
    for (final call in calls) {
      // Sync with all entities
      _syncWithSipAgent(call);
      _syncWithCallScreen(call);
      _syncWithApi(call);
    }
  }

  void _syncWithSipAgent(Call call) {
    switch (call.state) {
      case CallState.ringing:
        if (!_sipAgent.hasIncomingCall(call.uuid)) {
          // CallKit received call before SIP agent, catch it up
          _sipAgent.notifyIncomingCall(call);
        }
        break;
      case CallState.active:
        _sipAgent.answerCall(call.uuid);
        break;
      case CallState.ended:
        _sipAgent.endCall(call.uuid);
        break;
      // ... other states
    }
  }

  void _syncWithCallScreen(Call call) {
    switch (call.state) {
      case CallState.ringing:
        _callScreenController.showIncomingCallScreen(call);
        break;
      case CallState.active:
        _callScreenController.switchToActiveCallScreen(call);
        break;
      case CallState.ended:
        _callScreenController.dismissCallScreen(call.uuid);
        break;
      // ... other states
    }
  }

  void _syncWithApi(Call call) {
    // Report state changes to backend
    _apiClient.reportCallState(
      callUuid: call.uuid,
      state: call.state.name,
      timestamp: DateTime.now(),
    );
  }

  void dispose() {
    _stateSubscription?.cancel();
  }
}
```

#### 2. Handle Bidirectional State Changes

**SIP Agent → CallKeep:**
```dart
class SipAgent {
  final FlutterCallkeep _callKeep;

  void onIncomingSipCall(SipCallEvent event) async {
    // SIP received call, report to CallKit
    final result = await _callKeep.displayIncomingCallWithResult(
      event.callUuid,
      event.remoteUri,
      localizedCallerName: event.callerName,
    );

    result.fold(
      onSuccess: (call) {
        // CallKit accepted, continue SIP signaling
        sendSipRinging(event.callUuid);
      },
      onError: (error) {
        // CallKit rejected (e.g., DND), reject SIP call
        sendSipReject(event.callUuid, 486); // Busy Here
      },
    );
  }

  void onSipCallConnected(String uuid) {
    // SIP call connected, but don't need to tell CallKit
    // CallStateStore already updated when user pressed Answer
    // Just ensure media is ready
    startMedia(uuid);
  }
}
```

**User Action (CallKit) → SIP Agent:**
```dart
// Already handled by callStateStream subscription above
// When CallKit reports user answered, stream emits CallState.active,
// which triggers _syncWithSipAgent, which calls sipAgent.answerCall()
```

#### 3. Error Recovery

**Scenario**: SIP call fails to connect, but CallKit thinks it's active

```dart
class SipAgent {
  final FlutterCallkeep _callKeep;

  void onSipCallFailed(String uuid, SipError error) async {
    // SIP layer failed, need to end CallKit call
    final result = await _callKeep.endCallWithResult(uuid);

    result.fold(
      onSuccess: (_) {
        // CallKit ended successfully
        showErrorToUser('Call failed: ${error.message}');
      },
      onError: (callKeepError) {
        // CallKit also failed to end call (edge case)
        // Log for debugging, try force end
        logger.error('Failed to end call in CallKit: $callKeepError');
        _callKeep.endAllCalls(); // Nuclear option
      },
    );
  }
}
```

#### 4. State Reconciliation on App Launch

**Scenario**: App was killed, relaunched via CallKit/ConnectionService

```dart
class HipcallCallManager {
  Future<void> initialize() async {
    // Set up state stream
    _stateSubscription = _callKeep.callStateStream.listen(/*...*/);

    // Get current state from CallKeep (what OS thinks)
    final activeCalls = await _callKeep.getActiveCalls();

    // Reconcile with SIP agent
    for (final call in activeCalls) {
      if (!_sipAgent.hasActiveCall(call.uuid)) {
        // CallKit has call but SIP doesn't - reinitialize SIP
        await _sipAgent.reconnectCall(call);
      }
    }

    // Reconcile with API
    await _apiClient.syncActiveCalls(activeCalls);
  }
}
```

### Benefits of This Strategy

1. **Single Source of Truth**: `CallStateStore` is authoritative
2. **Automatic Sync**: All entities updated via stream
3. **Replay Capability**: Late subscribers get current state
4. **Error Recovery**: Clear error handling with Result types
5. **Testable**: Can mock stream, test sync logic
6. **Debuggable**: Single place to log all state changes

---

## Threading Model

### Current Problem

- iOS: Threading implicit, unclear which queue callbacks execute on
- Android: Mix of Handler posts, broadcasts, volatile flags
- Race conditions possible when coordinating with SIP agent on different thread

### Proposed: Explicit Threading Model

#### iOS Threading Guarantees

```objc
@interface CallKeepEventEmitter : NSObject

// All events emitted on main thread
- (void)emitEvent:(NSString *)eventName
             body:(NSDictionary *)body;

// Explicitly on background queue
- (void)emitEventAsync:(NSString *)eventName
                  body:(NSDictionary *)body
                 queue:(dispatch_queue_t)queue;

@end

@implementation CallKeepEventEmitter {
  FlutterMethodChannel *_channel;
}

- (void)emitEvent:(NSString *)eventName body:(NSDictionary *)body {
  dispatch_async(dispatch_get_main_queue(), ^{
    [self->_channel invokeMethod:eventName arguments:body];
  });
}

@end
```

**CXProvider Delegate Queue**:
```objc
@implementation CallKitManager

- (instancetype)initWithConfiguration:(CXProviderConfiguration *)config {
  if (self = [super init]) {
    // Explicit queue for CallKit callbacks
    dispatch_queue_t callKitQueue = dispatch_queue_create(
      "com.callkeep.callkit",
      DISPATCH_QUEUE_SERIAL
    );

    _provider = [[CXProvider alloc] initWithConfiguration:config];
    [_provider setDelegate:self queue:callKitQueue];
  }
  return self;
}

- (void)provider:(CXProvider *)provider performAnswerCallAction:(CXCallAction *)action {
  // Executes on callKitQueue (serial, background)

  // Do quick work here
  NSString *uuid = action.callUUID.UUIDString;

  // Emit event on main thread
  dispatch_async(dispatch_get_main_queue(), ^{
    [self.delegate callKitManager:self didAnswerCall:uuid];
  });

  [action fulfill];
}

@end
```

**Documented Guarantee**: All CallKeep events delivered on main thread

#### Android Threading Guarantees

```java
public class CallKeepEventEmitter {
  private final MethodChannel channel;
  private final Handler mainHandler;

  public CallKeepEventEmitter(MethodChannel channel) {
    this.channel = channel;
    this.mainHandler = new Handler(Looper.getMainLooper());
  }

  // All events emitted on main thread
  public void emitEvent(String eventName, Map<String, Object> body) {
    mainHandler.post(() -> {
      channel.invokeMethod(eventName, body);
    });
  }

  // Explicitly on background thread
  public void emitEventAsync(String eventName, Map<String, Object> body, Executor executor) {
    executor.execute(() -> {
      channel.invokeMethod(eventName, body);
    });
  }
}
```

**ConnectionService Callbacks**:
```java
public class VoiceConnection extends Connection {
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  @Override
  public void onAnswer() {
    // Executes on Binder thread (background)

    String uuid = this.callUuid;

    // Update state on main thread
    mainHandler.post(() -> {
      stateStore.transitionCall(uuid, CallState.ACTIVE);
    });

    // Emit event on main thread
    eventEmitter.emitEvent("CallKeepPerformAnswerCallAction",
      Map.of("callUUID", uuid));
  }
}
```

**Documented Guarantee**: All CallKeep events delivered on main thread

#### Dart Threading

**Flutter Side**: All events received on platform thread (main isolate)

```dart
class FlutterCallkeep {
  final _methodChannel = MethodChannel('FlutterCallKeep.Method');
  final _eventChannel = MethodChannel('FlutterCallKeep.Event');

  FlutterCallkeep() {
    // Event handler executes on main isolate
    _eventChannel.setMethodCallHandler(_handleEvent);
  }

  Future<void> _handleEvent(MethodCall call) async {
    // Already on main isolate, safe to update state
    final event = _mapToEvent(call);

    // Update state store (synchronous)
    _updateStateFromEvent(event);

    // Notify observers
    _eventManager.emit(event);
  }
}
```

**If Heavy Processing Needed**: Use isolate

```dart
class HipcallCallManager {
  Isolate? _processingIsolate;

  void _handleCallStateUpdate(List<Call> calls) {
    // Quick sync operations on main isolate
    _syncWithCallScreen(calls);

    // Heavy processing (e.g., audio processing) on background isolate
    _processingIsolate?.send(calls);
  }
}
```

### Threading Summary

| Platform | Event Emission Thread | State Update Thread | Callback Thread |
|----------|----------------------|---------------------|-----------------|
| iOS      | Main queue           | Main queue          | Main queue (guaranteed) |
| Android  | Main thread (Handler)| Main thread         | Main thread (guaranteed) |
| Dart     | Main isolate         | Main isolate        | Main isolate |

**Benefits**:
- Predictable threading (always main)
- No race conditions in event handling
- SIP agents can safely update UI
- Easy to reason about

---

## Error Handling Architecture

### Current Problems

1. Errors logged but not propagated to Flutter
2. No structured error types
3. Silent failures common
4. Apps can't recover from errors

### Proposed: Comprehensive Error Handling

#### Error Type Hierarchy

```dart
// Base error type
sealed class CallError implements Exception {
  final String message;
  final String? remediation;
  final StackTrace? stackTrace;

  const CallError(this.message, [this.remediation, this.stackTrace]);

  @override
  String toString() => 'CallError: $message${remediation != null ? '\nRemediation: $remediation' : ''}';
}

// Platform errors
class CallKitError extends CallError {
  final int errorCode;

  const CallKitError(this.errorCode, String message, [String? remediation])
    : super(message, remediation);
}

class TelecomError extends CallError {
  final int disconnectCause;

  const TelecomError(this.disconnectCause, String message, [String? remediation])
    : super(message, remediation);
}

// Permission errors
class PermissionError extends CallError {
  final List<String> missingPermissions;

  PermissionError(this.missingPermissions)
    : super(
        'Missing permissions: ${missingPermissions.join(", ")}',
        'Request permissions using FlutterCallkeep.setup() or manually via permission_handler'
      );
}

// State errors
class StateTransitionError extends CallError {
  final CallState? fromState;
  final CallState toState;

  StateTransitionError(this.fromState, this.toState)
    : super(
        'Invalid state transition from ${fromState ?? 'none'} to $toState',
        'Check call state before performing actions'
      );
}

// Configuration errors
class ConfigurationError extends CallError {
  final String field;

  ConfigurationError(this.field, String message)
    : super('Configuration error in $field: $message');
}

// Timing errors
class VoipPushTimeoutError extends CallError {
  VoipPushTimeoutError()
    : super(
        'Failed to report call to CallKit within 1 second',
        'Optimize push payload processing. Consider pre-parsing payload on server side.'
      );
}

class WakeUpTimeoutError extends CallError {
  WakeUpTimeoutError()
    : super(
        'ConnectionService did not respond within 2 seconds',
        'Check if app is being killed by battery optimization. Add to whitelist.'
      );
}

// Unknown errors
class UnknownError extends CallError {
  UnknownError(String message) : super('Unknown error: $message');
}
```

#### Result Type

```dart
sealed class Result<T> {
  const Result();

  R fold<R>({
    required R Function(T value) onSuccess,
    required R Function(CallError error) onError,
  });

  bool get isSuccess;
  bool get isFailure;
  T get valueOrNull;
  CallError get errorOrNull;
}

class Success<T> extends Result<T> {
  final T value;

  const Success(this.value);

  @override
  R fold<R>({
    required R Function(T value) onSuccess,
    required R Function(CallError error) onError,
  }) => onSuccess(value);

  @override
  bool get isSuccess => true;

  @override
  bool get isFailure => false;

  @override
  T get valueOrNull => value;

  @override
  CallError get errorOrNull => null;
}

class Failure<T> extends Result<T> {
  final CallError error;

  const Failure(this.error);

  @override
  R fold<R>({
    required R Function(T value) onSuccess,
    required R Function(CallError error) onError,
  }) => onError(error);

  @override
  bool get isSuccess => false;

  @override
  bool get isFailure => true;

  @override
  T get valueOrNull => null;

  @override
  CallError get errorOrNull => error;
}
```

#### Usage in App

```dart
// Method calls return Result
Future<void> handleIncomingCall(String uuid, String handle) async {
  final result = await callKeep.displayIncomingCallWithResult(uuid, handle);

  result.fold(
    onSuccess: (call) {
      // Call displayed successfully
      logger.info('Call displayed: ${call.uuid}');
      sipAgent.startRinging(uuid);
    },
    onError: (error) {
      // Handle specific errors
      switch (error) {
        case PermissionError():
          showPermissionDialog(error.missingPermissions);
        case CallKitError():
          if (error.errorCode == CXErrorCodeIncomingCallError.unknown) {
            // CallKit failed, try fallback
            showCustomIncomingCallUI(uuid, handle);
          }
        case StateTransitionError():
          // Already in a call
          sendBusySignal(uuid);
        default:
          showErrorDialog(error.message);
      }
    },
  );
}

// Error events for async failures
callKeep.on<CallKeepErrorEvent>((event) {
  logger.error('CallKeep error: ${event.error}');

  switch (event.error) {
    case VoipPushTimeoutError():
      // Critical error, report to monitoring
      crashlytics.recordError(event.error, event.error.stackTrace);

    case WakeUpTimeoutError():
      // Show user guidance
      showSnackBar('App may be restricted by battery optimization');

    default:
      // Log for debugging
      logger.warn('Unhandled error: ${event.error}');
  }
});
```

#### Error Propagation (Native → Dart)

**iOS**:
```objc
- (void)reportNewIncomingCall:(NSUUID *)uuid
                       handle:(NSString *)handle
            completionHandler:(void(^)(NSError *))completion {

  CXCallUpdate *update = [[CXCallUpdate alloc] init];
  update.remoteHandle = [[CXHandle alloc] initWithType:CXHandleTypeGeneric value:handle];

  [self.provider reportNewIncomingCallWithUUID:uuid
                                        update:update
                                    completion:^(NSError *error) {
    if (error) {
      // Map CXError to CallKeepError
      NSDictionary *errorDict = @{
        @"code": @(error.code),
        @"message": error.localizedDescription,
        @"domain": error.domain
      };

      // Return error to Flutter
      completion(errorDict);
    } else {
      completion(nil);
    }
  }];
}
```

**Android**:
```java
public Result<Call> displayIncomingCall(String uuid, String handle) {
  // Validate permissions
  if (!hasRequiredPermissions()) {
    List<String> missing = getMissingPermissions();
    return Result.failure(new PermissionError(missing));
  }

  // Validate state
  Result<Call> stateResult = stateStore.transitionCall(uuid, CallState.RINGING);
  if (stateResult.isFailure()) {
    return stateResult;
  }

  // Create incoming call
  try {
    Bundle extras = new Bundle();
    extras.putString("uuid", uuid);
    extras.putString("handle", handle);

    telecomManager.addNewIncomingCall(phoneAccountHandle, extras);

    return Result.success(stateResult.getValue());

  } catch (SecurityException e) {
    return Result.failure(new PermissionError(Arrays.asList("CALL_PHONE")));
  } catch (Exception e) {
    return Result.failure(new TelecomError(-1, e.getMessage()));
  }
}
```

### Benefits

- Apps can handle errors gracefully
- Clear remediation guidance
- Type-safe error handling (pattern matching)
- Errors propagated consistently across platforms
- Easy to add monitoring/crash reporting

---

## Configuration & Settings

### Current Problems

1. iOS: `NSDictionary` in `UserDefaults` (no validation)
2. Android: JSON string in `SharedPreferences` (no schema)
3. No versioning, breaking changes possible
4. No validation of required fields

### Proposed: Typed Configuration

```dart
class CallKeepConfig {
  // Required fields
  final String appName;

  // Optional fields with defaults
  final String? imageName;
  final String? ringtoneSound;
  final HandleType handleType;
  final bool supportsVideo;
  final int maximumCallGroups;
  final int maximumCallsPerCallGroup;
  final bool includesCallsInRecents;

  // Android-specific
  final AndroidConfig? android;

  // iOS-specific
  final IOSConfig? ios;

  const CallKeepConfig({
    required this.appName,
    this.imageName,
    this.ringtoneSound,
    this.handleType = HandleType.generic,
    this.supportsVideo = false,
    this.maximumCallGroups = 1,
    this.maximumCallsPerCallGroup = 1,
    this.includesCallsInRecents = true,
    this.android,
    this.ios,
  });

  // Validation
  Result<void> validate() {
    if (appName.isEmpty) {
      return Failure(ConfigurationError('appName', 'cannot be empty'));
    }

    if (maximumCallGroups < 1) {
      return Failure(ConfigurationError('maximumCallGroups', 'must be >= 1'));
    }

    // Platform-specific validation
    if (Platform.isAndroid && android != null) {
      return android!.validate();
    }

    if (Platform.isIOS && ios != null) {
      return ios!.validate();
    }

    return Success(null);
  }

  // Backward compatible factory
  factory CallKeepConfig.fromMap(Map<String, dynamic> map) {
    return CallKeepConfig(
      appName: map['appName'] as String,
      imageName: map['imageName'] as String?,
      // ... map all fields
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'appName': appName,
      'imageName': imageName,
      // ... all fields
    };
  }
}

class AndroidConfig {
  final List<String>? additionalPermissions;
  final bool isSelfManaged;
  final String handleSchema;
  final ForegroundServiceConfig? foregroundService;

  const AndroidConfig({
    this.additionalPermissions,
    this.isSelfManaged = false,
    this.handleSchema = 'tel',
    this.foregroundService,
  });

  Result<void> validate() {
    // Android 11+ requires foreground service
    if (Build.VERSION.SDK_INT >= 30 && foregroundService == null) {
      return Failure(ConfigurationError(
        'foregroundService',
        'required for Android 11+',
      ));
    }

    return Success(null);
  }
}

class ForegroundServiceConfig {
  final String channelId;
  final String channelName;
  final String notificationTitle;
  final String notificationIcon;
  final int notificationId;

  const ForegroundServiceConfig({
    required this.channelId,
    required this.channelName,
    required this.notificationTitle,
    required this.notificationIcon,
    this.notificationId = 12345,
  });

  Result<void> validate() {
    if (channelId.isEmpty) {
      return Failure(ConfigurationError('channelId', 'cannot be empty'));
    }
    // ... validate other fields

    return Success(null);
  }
}
```

#### Setup with Validation

```dart
Future<void> setupCallKeep() async {
  final config = CallKeepConfig(
    appName: 'Hipcall',
    handleType: HandleType.number,
    supportsVideo: true,
    android: AndroidConfig(
      foregroundService: ForegroundServiceConfig(
        channelId: 'hipcall_calls',
        channelName: 'Hipcall Calls',
        notificationTitle: 'Ongoing call',
        notificationIcon: 'ic_phone',
      ),
    ),
  );

  // Validate configuration
  final validation = config.validate();
  if (validation.isFailure) {
    print('Invalid config: ${validation.errorOrNull}');
    return;
  }

  // Setup with validated config
  final result = await callKeep.setupWithResult(config);
  result.fold(
    onSuccess: (_) => print('CallKeep setup successful'),
    onError: (error) => print('Setup failed: $error'),
  );
}
```

### Benefits

- Type-safe configuration
- Validation before setup
- Clear error messages for missing/invalid fields
- Schema versioning possible (add version field)
- Platform-specific config isolated

---

## Testing Strategy

### Current State

**No unit tests visible in codebase**

### Proposed: Comprehensive Testing

#### Unit Tests (Dart)

```
test/
├── domain/
│   ├── call_state_machine_test.dart   # State transition logic
│   ├── call_state_store_test.dart     # State management
│   └── usecases/
│       ├── answer_call_test.dart
│       ├── start_call_test.dart
│       └── end_call_test.dart
├── data/
│   ├── platform_call_repository_test.dart  # With mock platform channel
│   ├── call_mapper_test.dart               # Data mapping
│   └── error_mapper_test.dart              # Error mapping
├── presentation/
│   └── callkeep_api_test.dart         # Public API tests
└── mocks/
    ├── mock_platform_channel.dart
    ├── mock_call_repository.dart
    └── mock_state_store.dart
```

**Example Test**:
```dart
void main() {
  group('CallStateMachine', () {
    late CallStateMachine stateMachine;

    setUp(() {
      stateMachine = CallStateMachine();
    });

    test('should allow transition from ringing to active', () {
      final result = stateMachine.canTransition(
        from: CallState.ringing,
        to: CallState.active,
      );

      expect(result.isValid, true);
    });

    test('should not allow transition from ringing to held', () {
      final result = stateMachine.canTransition(
        from: CallState.ringing,
        to: CallState.held,
      );

      expect(result.isValid, false);
      expect(result.reason, contains('Cannot hold ringing call'));
    });

    test('should allow transition from active to held', () {
      final result = stateMachine.canTransition(
        from: CallState.active,
        to: CallState.held,
      );

      expect(result.isValid, true);
    });
  });

  group('CallStateStore', () {
    late CallStateStore store;

    setUp(() {
      store = CallStateStore();
    });

    test('should emit state updates to subscribers', () async {
      final states = <Map<String, Call>>[];
      store.callStates.listen(states.add);

      final call = Call(uuid: '123', state: CallState.ringing);
      store.addCall(call);

      await Future.delayed(Duration(milliseconds: 10));

      expect(states.length, 1);
      expect(states[0]['123']!.state, CallState.ringing);
    });

    test('should validate state transitions', () {
      final call = Call(uuid: '123', state: CallState.ringing);
      store.addCall(call);

      // Valid transition
      final result1 = store.transitionCall('123', CallState.active);
      expect(result1.isSuccess, true);

      // Invalid transition
      final result2 = store.transitionCall('123', CallState.ringing);
      expect(result2.isFailure, true);
      expect(result2.errorOrNull, isA<StateTransitionError>());
    });
  });
}
```

#### Integration Tests (Dart)

```
integration_test/
├── call_flow_test.dart           # Full call lifecycle
├── error_handling_test.dart      # Error scenarios
└── state_sync_test.dart          # State synchronization
```

#### Native Tests (iOS)

```
ios/Tests/
├── CallStateMachineTests.m       # State machine logic
├── CallStateStoreTests.m         # State store
├── CallKitManagerTests.m         # CallKit integration (mocked)
├── PushKitManagerTests.m         # VoIP push handling
└── Mocks/
    ├── MockCXProvider.h
    └── MockPKPushRegistry.h
```

**Example Test (iOS)**:
```objc
@interface CallStateMachineTests : XCTestCase
@property (nonatomic, strong) CallStateMachine *stateMachine;
@end

@implementation CallStateMachineTests

- (void)setUp {
  self.stateMachine = [[CallStateMachine alloc] init];
}

- (void)testValidTransitionFromRingingToActive {
  ValidationResult *result = [self.stateMachine canTransitionFrom:CallStateRinging
                                                               to:CallStateActive];
  XCTAssertTrue(result.isValid);
}

- (void)testInvalidTransitionFromRingingToHeld {
  ValidationResult *result = [self.stateMachine canTransitionFrom:CallStateRinging
                                                               to:CallStateHeld];
  XCTAssertFalse(result.isValid);
  XCTAssertNotNil(result.reason);
}

@end
```

#### Native Tests (Android)

```
android/src/test/java/
├── CallStateMachineTest.java
├── CallStateStoreTest.java
├── VoiceConnectionServiceTest.java
└── mocks/
    ├── MockTelecomManager.java
    └── MockConnection.java
```

**Example Test (Android)**:
```java
public class CallStateMachineTest {
  private CallStateMachine stateMachine;

  @Before
  public void setUp() {
    stateMachine = new CallStateMachine();
  }

  @Test
  public void testValidTransitionFromRingingToActive() {
    ValidationResult result = stateMachine.canTransition(
      CallState.RINGING,
      CallState.ACTIVE
    );

    assertTrue(result.isValid());
  }

  @Test
  public void testInvalidTransitionFromRingingToHeld() {
    ValidationResult result = stateMachine.canTransition(
      CallState.RINGING,
      CallState.HELD
    );

    assertFalse(result.isValid());
    assertNotNull(result.getReason());
    assertTrue(result.getReason().contains("Cannot hold ringing call"));
  }
}
```

### Test Coverage Goals

- **Domain Layer**: >90% (pure logic, easy to test)
- **Data Layer**: >80% (with mocked platform channels)
- **Native Layer**: >70% (with mocked system frameworks)
- **Integration Tests**: Critical flows covered

---

## Implementation Phases

### Phase 1: Foundation (4-6 weeks)

**Goal**: Core reliability improvements without breaking changes

**Tasks**:
1. Implement domain layer (Dart)
   - Call state machine
   - CallStateStore
   - Error types
   - Result types
   - Use cases
2. Add unit tests for domain layer
3. Implement CallStateStore (iOS + Android native)
4. Add threading guarantees
5. Refactor event emission to guarantee main thread

**Deliverable**: Internal refactoring, public API unchanged, improved reliability

**Testing**: Unit tests pass, existing functionality works

---

### Phase 2: Native Refactoring (4-6 weeks)

**Goal**: Eliminate god objects, improve testability

**Tasks**:
1. iOS:
   - Split `CallKeep.m` into services (CallKitManager, AudioSessionManager, PushKitManager)
   - Implement dependency injection
   - Add native unit tests

2. Android:
   - Split `CallKeepModule.java` into services
   - Implement ServiceContainer
   - Refactor VoiceConnectionService
   - Add native unit tests

**Deliverable**: Cleaner codebase, testable services, public API unchanged

**Testing**: Native unit tests, integration tests

---

### Phase 3: Observable State & Error Handling (3-4 weeks)

**Goal**: Add observable state stream and comprehensive error handling

**Tasks**:
1. Implement `callStateStream` (opt-in)
2. Add `*WithResult` methods (opt-in)
3. Implement error mapping (native → Dart)
4. Add error events
5. Add event unsubscribe support
6. Documentation for new features

**Deliverable**: New features available (opt-in), backward compatible

**Testing**: Integration tests for state sync, error scenarios

---

### Phase 4: Polish & Documentation (2-3 weeks)

**Goal**: Production-ready release

**Tasks**:
1. Performance benchmarking
2. Memory leak detection
3. Comprehensive documentation
4. Example app (Hipcall-style with SIP)
5. Migration guide
6. API reference documentation

**Deliverable**: Production-ready release

---

## Migration Guide (For Reference)

### No Changes Required

Existing code continues to work:

```dart
// Current code - works identically
final callKeep = FlutterCallkeep();

await callKeep.setup({
  'appName': 'Hipcall',
  // ... existing options
});

await callKeep.displayIncomingCall(uuid, handle);

callKeep.on<CallKeepPerformAnswerCallAction>((event) {
  // Handle answer
});
```

### Opt-in to New Features (When Ready)

```dart
// Use typed configuration (optional)
final config = CallKeepConfig(
  appName: 'Hipcall',
  android: AndroidConfig(
    foregroundService: ForegroundServiceConfig(/*...*/),
  ),
);

await callKeep.setupWithResult(config);

// Use observable state for sync (recommended for SIP)
callKeep.callStateStream.listen((calls) {
  sipAgent.syncCalls(calls);
});

// Use result types for better error handling (optional)
final result = await callKeep.displayIncomingCallWithResult(uuid, handle);
result.fold(
  onSuccess: (call) => print('Success'),
  onError: (error) => handleError(error),
);

// Unsubscribe from events (optional)
final subscription = callKeep.on<CallKeepPerformAnswerCallAction>(/*...*/);
// Later:
subscription.cancel();
```

---

## Benefits Summary

### Reliability Improvements

| Issue | Current | After Rewrite |
|-------|---------|---------------|
| **State Management** | Scattered, no validation | Single source of truth, validated |
| **State Sync** | Manual event handling | Observable streams |
| **Error Handling** | Silent failures | Result types, error events |
| **Threading** | Unclear, race conditions possible | Guaranteed main thread |
| **VoIP Push** | Fragile, recent revert | Timeout protection, validation |
| **Wake-up** | Complex CountDownLatch | Simplified with timeout |
| **Testing** | Hard, no tests | Easy, comprehensive tests |

### For Your Use Case (Hipcall)

**SIP Agent Sync**: Observable `callStateStream` keeps SIP in sync

**Call Screen Sync**: Same stream updates UI automatically

**API Sync**: Same stream reports to backend

**Error Recovery**: Result types allow graceful handling of failures

**Debugging**: Single CallStateStore makes debugging state issues trivial

**Testability**: Can mock all dependencies, test SIP coordination logic

---

## Risks & Mitigation

### Risk 1: Backward Compatibility Breaks

**Mitigation**:
- Extensive integration tests
- Beta release period
- Keep old methods alongside new ones
- Clear deprecation warnings

### Risk 2: Performance Regression

**Mitigation**:
- Benchmark before/after
- Profile state updates
- Optimize hot paths
- Use BehaviorSubject (replay) instead of broadcast streams

### Risk 3: Platform Behavior Changes (iOS/Android)

**Mitigation**:
- Abstract platform specifics behind interfaces
- Test on multiple OS versions
- Monitor Apple/Google documentation
- Keep platform-specific code isolated

### Risk 4: Adoption of New Features

**Mitigation**:
- Clear documentation with examples
- Migration guide showing benefits
- Example app demonstrating SIP integration
- Optional adoption (backward compatible)

---

## Conclusion

This architecture rewrite focuses on **reliability through predictable state management**, **consistency through single source of truth**, and **real-time synchronization through observable streams** - exactly what's needed for coordinating CallKeep with SIP agents, call screens, and 3rd party APIs in the Hipcall app.

**Key Differentiators**:
- Drop-in replacement (zero breaking changes)
- Observable state for multi-entity sync
- Comprehensive error handling with recovery
- Testable architecture (>80% coverage goal)
- Explicit threading model
- Validated state transitions

**Timeline**: 13-19 weeks for full implementation

**Recommendation**: Implement in phases, ship each phase incrementally to reduce risk.
