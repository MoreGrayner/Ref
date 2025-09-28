# REF

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF.svg?logo=kotlin)](https://kotlinlang.org/)
[![Coroutines](https://img.shields.io/badge/Coroutines-1.8.1-blue.svg?logo=kotlin)](https://github.com/Kotlin/kotlinx.coroutines)

### 다중 상태 관리 지원 라이브러리

---

* ### Features
  * 원자적 상태(_AtomicRef_) - 스레드 안전한 방식으로 상태 관리 + 관찰
  * 불변 참조(_ImmutableRef_) - 값을 불변의 객체로 감싸 안전성 보장
  * 지연 초기화 참조(_LazyRef_) - 초기화 지연 및 초기화 재실행
  * 계산된 참조(_ComputedRef_) - Readable 객체에 의존한 동적으로 값을 계산
  * 다중 참조 관리(_MultiRef_) - Transformable 참조를 하나의 그룹으로 묶어 관리
  * 시간 기반 참조(_TimedRef_) - 설정된 시간(TTL)이 지나면 자동으로 만료되는 상태 관리
  * 캐시 참조(_CachedRef_) - 키-값 쌍을 캐싱으로 반복적인 계산을 방지 및 성능 향상
  * 상태 머신 참조(_StateMachineRef_) - 명확하게 정의된 상태와 전이 규칙을 기반으로 상태 관리
  * 플로우 체인(_RefChain_) - Flow 기반 다양한 연산자를 체인 형태로 조합하여 데이터 스트림 처리
  * 리액티브 커맨드(_ReactiveCommand_) - 비동기 작업을 캡슐화하여 실행 상태와 결과를 Flow로 관찰 가능
  * 스토어(_Store_) -  Redux와 유사한 단방향 데이터 흐름 아키텍처 구현 지원
  * 콜백(_CallBack_) - 업데이트당 값을 할당하는 콜백 기능 지원

    
### AtomicRef

`AtomicRef`는 가장 기본적인 스레드 안전 상태 홀더입니다. `value` 프로퍼티를 통해 직접 값을 읽거나 쓸 수 있으며, `emit` 또는 `transform`과 같은 함수를 사용하여 비동기적으로 값을 업데이트할 수 있습니다.

```kotlin
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.AtomicRef

fun main() = runBlocking {
    val count = AtomicRef(0)

    // 값 변경 관찰
    val job = count.observe(this) { value ->
        println("Count is: $value")
    }

    // 값 업데이트
    count.emit(1)
    count.transform { it + 1 }

    // 최종 값: 2
    println("Final count: ${count.value}")

    job.cancel()
}
```

### ImmutableRef

`ImmutableRef`는 변경할 수 없는 값을 감싸서 안정성을 보장합니다. `map`, `flatMap`, `filter`와 같은 함수형 연산을 지원합니다.

```kotlin
import io.github.moregrayner.flowx.Ref.ImmutableRef

fun main() {
    val name = ImmutableRef("Alice")
    println("Name: ${name.value}")

    val upperCaseName = name.map { it.uppercase() }
    println("Uppercase Name: ${upperCaseName.value}")

    val filteredName = name.filter { it.startsWith("A") }
    println("Filtered Name: ${filteredName.value}")
}
```

### LazyRef

`LazyRef`는 값이 처음으로 필요할 때 초기화 로직을 실행하여 리소스를 효율적으로 사용합니다. `refresh`를 통해 값을 다시 초기화할 수 있습니다.

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.LazyRef

fun main() = runBlocking {
    var counter = 0
    val lazyValue = LazyRef { 
        println("Initializing lazy value...")
        delay(100)
        counter++
    }

    println("Before accessing lazyValue: ${counter}")
    println("First access: ${lazyValue.value}") // 초기화 로직 실행
    println("Second access: ${lazyValue.value}") // 초기화 로직 다시 실행 안함

    println("Refreshing lazyValue...")
    println("Refreshed value: ${lazyValue.refresh()}") // 초기화 로직 다시 실행
    println("After refreshing lazyValue: ${counter}")
}
```

### ComputedRef

`ComputedRef`는 다른 `Readable` 객체에 의존하여 값을 계산합니다. 원본 값이 변경되면 `ComputedRef`의 값도 자동으로 업데이트됩니다.

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.AtomicRef
import io.github.moregrayner.flowx.Ref.ComputedRef

fun main() = runBlocking {
    val firstName = AtomicRef("John")
    val lastName = AtomicRef("Doe")

    val fullName = ComputedRef(listOf(firstName, lastName)) { values ->
        "${values[0]} ${values[1]}"
    }

    val job = fullName.observe(this) { name ->
        println("Full name: $name")
    }

    delay(100)
    lastName.emit("Smith")

    delay(100)
    job.cancel()
}
```

### MultiRef

`MultiRef`는 여러 `Transformable` 참조를 하나의 그룹으로 묶어 관리합니다. 다양한 업데이트 전략을 제공합니다.

```kotlin
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.AtomicRef
import io.github.moregrayner.flowx.Ref.MultiRef

fun main() = runBlocking {
    val ref1 = AtomicRef(1)
    val ref2 = AtomicRef(2)
    val ref3 = AtomicRef(3)

    val multiRef = MultiRef(listOf(ref1, ref2, ref3), MultiRef.UpdateStrategy.ALL)

    multiRef.observe(this) { values ->
        println("MultiRef values: $values")
    }

    multiRef.emit(listOf(10, 20, 30))
    println("Individual refs after emit: ${ref1.value}, ${ref2.value}, ${ref3.value}")

    multiRef.transform { currentList -> currentList.map { it * 2 } }
    println("Individual refs after transform: ${ref1.value}, ${ref2.value}, ${ref3.value}")
}
```

### TimedRef

`TimedRef`는 설정된 시간(TTL)이 지나면 자동으로 만료되는 상태를 관리합니다.

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import io.github.moregrayner.flowx.Ref.TimedRef

fun main() = runBlocking {
    val data = TimedRef("Initial Data", 2.seconds) { expiredValue ->
        println("Data expired: $expiredValue. Resetting...")
        "Expired Data"
    }

    println("Current data: ${data.value}")
    delay(1.seconds)
    println("Remaining time: ${data.getRemainingTime()}")

    delay(2.seconds) // TTL 초과
    println("Current data after expiration: ${data.value}")

    data.emit("New Data")
    println("Data reset: ${data.value}")
    delay(1.seconds)
    println("Remaining time after reset: ${data.getRemainingTime()}")
}
```

### CachedRef

`CachedRef`는 키-값 쌍을 캐싱하여 반복적인 계산을 방지하고 성능을 향상시킵니다.

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds
import io.github.moregrayner.flowx.Ref.CachedRef

fun main() = runBlocking {
    var computationCount = 0
    val userCache = CachedRef<String, String>(
        computer = { userId ->
            computationCount++
            println("Fetching user data for $userId...")
            delay(500) // Simulate network request
            "User: $userId, Details: Fetched at ${System.currentTimeMillis()}"
        },
        maxSize = 2,
        ttl = 5.seconds
    )

    println(userCache.get("user1")) // Fetch
    println(userCache.get("user2")) // Fetch
    println(userCache.get("user1")) // From cache

    delay(6.seconds) // Cache expires

    println(userCache.get("user1")) // Fetch again
    println("Computation count: $computationCount")

    userCache.get("user3") // Fetch, user1 will be evicted (LRU)
    println(userCache.get("user2")) // From cache
    println(userCache.get("user3")) // From cache
}
```

### StateMachineRef

`StateMachineRef`는 명확하게 정의된 상태와 전이 규칙을 기반으로 상태를 관리합니다.

```kotlin
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.StateMachineRef

fun main() = runBlocking {
    enum class State { IDLE, LOADING, SUCCESS, ERROR }
    enum class Event { LOAD, LOAD_SUCCESS, LOAD_FAIL, RESET }

    val transitions = mapOf(
        (State.IDLE to Event.LOAD) to State.LOADING,
        (State.LOADING to Event.LOAD_SUCCESS) to State.SUCCESS,
        (State.LOADING to Event.LOAD_FAIL) to State.ERROR,
        (State.SUCCESS to Event.RESET) to State.IDLE,
        (State.ERROR to Event.RESET) to State.IDLE
    )

    val stateMachine = StateMachineRef(State.IDLE, transitions)

    stateMachine.observe(this) { state ->
        println("Current State: $state")
    }

    stateMachine.processEvent(Event.LOAD)
    stateMachine.processEvent(Event.LOAD_SUCCESS)
    stateMachine.processEvent(Event.RESET)
    stateMachine.processEvent(Event.LOAD_FAIL) // 이 이벤트는 현재 상태(IDLE)에서 유효하지 않으므로 무시됨
    stateMachine.processEvent(Event.LOAD)
    stateMachine.processEvent(Event.LOAD_FAIL)
}
```

### RefChain

`RefChain`은 `Flow`를 기반으로 다양한 연산자를 체인 형태로 조합하여 데이터 스트림을 처리합니다.

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import io.github.moregrayner.flowx.Ref.RefChain
import io.github.moregrayner.flowx.Ref.AtomicRef

fun main() = runBlocking {
    val sourceRef = AtomicRef(0)

    RefChain.from(sourceRef)
        .map { it * 2 }
        .filter { it % 3 == 0 }
        .debounce(100.milliseconds)
        .onEach { println("Processed value: $it") }
        .launchIn(this)

    sourceRef.emit(1)
    delay(50.milliseconds)
    sourceRef.emit(2)
    delay(50.milliseconds)
    sourceRef.emit(3) // (3 * 2) % 3 == 0
    delay(50.milliseconds)
    sourceRef.emit(4)
    delay(150.milliseconds) // debounce
    sourceRef.emit(5)
    delay(50.milliseconds)
    sourceRef.emit(6) // (6 * 2) % 3 == 0
    delay(150.milliseconds)
}
```

### ReactiveCommand

`ReactiveCommand`는 비동기 작업을 캡슐화하고 실행 상태 및 결과를 관찰할 수 있게 합니다.

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.ReactiveCommand

fun main() = runBlocking {
    val loginCommand = ReactiveCommand<Pair<String, String>, String>(
        executor = { (username, password) ->
            println("Attempting to log in with $username...")
            delay(1000) // Simulate network request
            if (username == "user" && password == "pass") {
                "Login successful for $username"
            } else {
                throw IllegalArgumentException("Invalid credentials")
            }
        },
        canExecute = { (username, password) ->
            username.isNotBlank() && password.isNotBlank()
        }
    )

    loginCommand.isExecuting.observe(this) { isExecuting ->
        println("Command is executing: $isExecuting")
    }

    loginCommand.results.observe(this) { result ->
        result.onSuccess { println("Command result: $it") }
              .onFailure { println("Command failed: ${it.message}") }
    }

    loginCommand.execute("user", "pass")
    delay(1500)

    loginCommand.execute("wrong", "pass")
    delay(1500)

    loginCommand.execute("", "") // canExecute 조건 불만족
    delay(100)
}
```

### Store

`Store`는 Redux와 유사한 단방향 데이터 흐름 아키텍처를 구현할 수 있도록 지원합니다.

```kotlin
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.Store

fun main() = runBlocking {
    data class AppState(val count: Int = 0, val message: String = "")
    sealed class AppAction { object Increment : AppAction(); data class SetMessage(val msg: String) : AppAction() }

    class CounterStore : Store<AppState, AppAction>(AppState()) {
        override suspend fun reduce(state: AppState, action: AppAction): AppState {
            return when (action) {
                AppAction.Increment -> state.copy(count = state.count + 1)
                is AppAction.SetMessage -> state.copy(message = action.msg)
            }
        }

        override suspend fun onStateChanged(oldState: AppState, newState: AppState, action: AppAction) {
            println("State changed from $oldState to $newState by action $action")
        }
    }

    val store = CounterStore()

    store.state.observe(this) { state ->
        println("Current App State: $state")
    }

    store.dispatch(AppAction.Increment)
    store.dispatch(AppAction.SetMessage("Hello FlowX!"))
    store.dispatch(AppAction.Increment)

    // 최종 상태 확인
    println("Final App State: ${store.state.value}")
}
```

### Memoizer

`Memoizer`는 함수의 결과를 캐싱하여 동일한 입력에 대해 다시 계산하는 것을 방지합니다.

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.Memoizer

fun main() = runBlocking {
    val memoizer = Memoizer<Int, String>()
    var computationCount = 0

    suspend fun expensiveFunction(input: Int): String {
        computationCount++
        println("Computing for $input...")
        delay(200) // Simulate expensive computation
        return "Result for $input"
    }

    println(memoizer.memoize(1) { expensiveFunction(1) })
    println(memoizer.memoize(2) { expensiveFunction(2) })
    println(memoizer.memoize(1) { expensiveFunction(1) }) // From cache
    println(memoizer.memoize(3) { expensiveFunction(3) })

    println("Total computations: $computationCount")

    memoizer.invalidate(2)
    println(memoizer.memoize(2) { expensiveFunction(2) }) // Re-computed
    println("Total computations after invalidate: $computationCount")
}
```

### MockRef

`MockRef`는 테스트 환경에서 `AtomicRef`의 동작을 모의(mock)하고 방출된 값을 기록하는 데 사용됩니다.

```kotlin
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.MockRef

fun main() = runBlocking {
    val mockCounter = MockRef(0)

    mockCounter.emit(1)
    mockCounter.emit(2)
    mockCounter.transform { it + 1 }

    println("Emissions: ${mockCounter.emissions}") // [1, 2, 3]
    println("Last emission: ${mockCounter.getLastEmission()}") // 3
    println("Emission count: ${mockCounter.getEmissionCount()}") // 3

    mockCounter.clearEmissions()
    println("Emissions after clear: ${mockCounter.emissions}") // []
}
```

### TestScheduler

`TestScheduler`는 코루틴의 시간 흐름을 제어하여 테스트를 용이하게 합니다.

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import io.github.moregrayner.flowx.Ref.TestScheduler

fun main() = runBlocking {
    val scheduler = TestScheduler()
    var eventLog = mutableListOf<String>()

    scheduler.schedule(100.milliseconds) { eventLog.add("Event A at ${scheduler.getCurrentTime()}ms") }
    scheduler.schedule(50.milliseconds) { eventLog.add("Event B at ${scheduler.getCurrentTime()}ms") }
    scheduler.schedule(120.milliseconds) { eventLog.add("Event C at ${scheduler.getCurrentTime()}ms") }

    scheduler.advanceTimeBy(60.milliseconds)
    println("Time: ${scheduler.getCurrentTime()}ms, Log: $eventLog")

    scheduler.advanceTimeBy(100.milliseconds)
    println("Time: ${scheduler.getCurrentTime()}ms, Log: $eventLog")
}
```

### RefMetrics

`RefMetrics`는 `Readable` 객체의 업데이트 횟수, 평균 업데이트 시간, 마지막 업데이트 시간 등의 메트릭을 모니터링합니다.

```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.AtomicRef
import io.github.moregrayner.flowx.Ref.RefMetrics

fun main() = runBlocking {
    val counter = AtomicRef(0)
    val metrics = RefMetrics(counter)

    metrics.startMonitoring(this)

    delay(100)
    counter.emit(1)
    delay(200)
    counter.emit(2)
    delay(150)
    counter.emit(3)

    val snapshot = metrics.getMetricsSnapshot()
    println("Metrics Snapshot: $snapshot")

    metrics.reset()
    println("Metrics after reset: ${metrics.getMetricsSnapshot()}")
}
```

## `bypass` 확장 함수

`bypass` 확장 함수는 값을 `Writable` 객체로 직접 전달하거나, 특정 조건 또는 변환을 거쳐 전달하는 편리한 방법을 제공합니다.

### 1. 기본 할당

값을 `Writable` 객체에 직접 `emit`합니다.

```kotlin
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.AtomicRef
import io.github.moregrayner.flowx.bypass

fun main() = runBlocking {
    val myRef = AtomicRef("Initial")
    myRef.observe(this) { println("myRef: $it") }

    "Hello World" bypass myRef
}
```

### 2. 조건부 할당

특정 조건이 참일 경우에만 값을 `Transformable` 객체에 `updateIf`를 사용하여 할당합니다.

```kotlin
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.AtomicRef
import io.github.moregrayner.flowx.Ref.BypassConditionalRef
import io.github.moregrayner.flowx.bypass

fun main() = runBlocking {
    val myRef = AtomicRef(10)
    myRef.observe(this) { println("myRef: $it") }

    // myRef의 현재 값이 10보다 클 경우에만 20으로 업데이트
    20 bypass BypassConditionalRef(myRef) { it > 10 }
    // 현재 10이므로 업데이트되지 않음
  
    //OnlyIf를 사용한 할당
    10 bypass myRef.onlyIf{it = 10}
    //이 경우에는 할당하는 값이 10 = it 으로 동일하므로 할당에 성공함

    // myRef의 현재 값이 5보다 클 경우에만 50으로 업데이트
    50 bypass BypassConditionalRef(myRef) { it > 5 }
    // 현재 10이므로 업데이트됨
}
```

### 3. 변환 후 할당

값을 `Writable` 객체에 할당하기 전에 주어진 `transform` 함수를 적용합니다.

```kotlin
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.AtomicRef
import io.github.moregrayner.flowx.Ref.BypassTransformRef
import io.github.moregrayner.flowx.bypass

fun main() = runBlocking {
    val myRef = AtomicRef(5)
    myRef.observe(this) { println("myRef: $it") }

    // 10을 받아서 2배로 변환한 후 myRef에 할당
    10 bypass BypassTransformRef(myRef) { it * 2 }
    // myRef는 20이 됨
}
```

### 4. 체인 변환 후 할당

값을 `Writable` 객체에 할당하기 전에 여러 `operations`를 체인 형태로 적용합니다.

```kotlin
import kotlinx.coroutines.runBlocking
import io.github.moregrayner.flowx.Ref.AtomicRef
import io.github.moregrayner.flowx.Ref.BypassChainRef
import io.github.moregrayner.flowx.bypass

fun main() = runBlocking {
    val myRef = AtomicRef(1)
    myRef.observe(this) { println("myRef: $it") }

    // 5를 받아서 (x + 1) * 2 변환을 적용한 후 myRef에 할당
    5 bypass BypassChainRef(myRef, listOf(
        { it + 1 },
        { it * 2 }
    ))
    // myRef는 (5+1)*2 = 12가 됨
}
```

### 5. 값 변동 시 자동 할당
값이 변경 시 자동으로 재할당됩니다.

```kotlin
    fun main(){
        val myAtomicRef = Ref.AtomicRef(0)
        myAtomicRef.onChange(GlobalScope) { old, new ->
        println("myAtomicRef 값이 변경되었습니다: 이전=$old, 새=$new")
        }
    }
```

## Contributors
* **[MoreGrayner](https://github.com/moregrayner)**
    * 알고리즘 설계 및 제작(코드가 길어 AI의 보조를 받음)


## 라이선스

**FlowX**는 [MIT 라이선스](https://opensource.org/licenses/MIT)에 따라 배포됩니다.

