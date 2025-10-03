@file:OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class, FlowPreview::class)
package io.github.moregrayner.flowx.io.github.moregrayner.flowx

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.*
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KProperty
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.pow

object Ref {

    class BypassConditionalRef<T>(val ref: Transformable<T>, val condition: suspend (T) -> Boolean)
    class BypassTransformRef<T>(val ref: Writable<T>, val transform: suspend (T) -> T)
    class BypassChainRef<T>(val target: Writable<T>, val operations: List<suspend (T) -> T>)

    class ConditionalRef<T>(val ref: Writable<T>, val condition: suspend (T) -> Boolean)
    class TransformRef<T>(val ref: Writable<T>, val transform: suspend (T) -> T)
    class ChainRef<T>(val target: Writable<T>, val operations: List<suspend (T) -> T>)

    interface Readable<out T> {
        val value: T
        val flow: Flow<T>
        fun snapshot(): T
    }

    interface Writable<T> : Readable<T> {
        suspend fun emit(newValue: T)
        fun tryEmit(newValue: T): Boolean
        override var value: T
    }

    interface Transformable<T> : Writable<T> {
        suspend fun transform(transformer: suspend (T) -> T): T
        suspend fun updateIf(predicate: suspend (T) -> Boolean, newValue: T): Boolean
        suspend fun updateIf(predicate: suspend (T) -> Boolean, updater: suspend (T) -> T): Boolean
    }

    interface Observable<T> : Readable<T> {
        fun observe(
            scope: CoroutineScope,
            context: CoroutineContext = Dispatchers.Default,
            onEach: suspend (T) -> Unit
        ): Job

        fun observeDistinct(
            scope: CoroutineScope,
            context: CoroutineContext = Dispatchers.Default,
            onEach: suspend (T) -> Unit
        ): Job

        fun observeChanges(
            scope: CoroutineScope,
            context: CoroutineContext = Dispatchers.Default,
            onChange: suspend (old: T, new: T) -> Unit
        ): Job
    }

    open class AtomicRef<T>(
        initialValue: T,
        bufferCapacity: Int = Channel.UNLIMITED,
        onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND
    ) : Transformable<T>, Observable<T> {

        private val atomicValue = AtomicReference(initialValue)
        private val _flow = MutableSharedFlow<T>(
            replay = 1,
            extraBufferCapacity = bufferCapacity,
            onBufferOverflow = onBufferOverflow
        )

        private val version = AtomicLong(0L)
        private val changeId = AtomicLong(0L)

        init {
            _flow.tryEmit(initialValue)
        }

        override var value: T
            get() = atomicValue.get()
            set(newValue) {
                val oldValue = atomicValue.getAndSet(newValue)
                if (oldValue != newValue) {
                    version.incrementAndGet()
                    _flow.tryEmit(newValue)
                }
            }

        override val flow: Flow<T>
            get() = _flow.asSharedFlow()

        override fun snapshot(): T = atomicValue.get()

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value
        operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T) {
            value = newValue
        }

        override suspend fun emit(newValue: T) {
            val oldValue = atomicValue.getAndSet(newValue)
            if (oldValue != newValue) {
                version.incrementAndGet()
                _flow.emit(newValue)
            }
        }

        override fun tryEmit(newValue: T): Boolean {
            val oldValue = atomicValue.getAndSet(newValue)
            return if (oldValue != newValue) {
                version.incrementAndGet()
                _flow.tryEmit(newValue)
            } else true
        }

        override suspend fun transform(transformer: suspend (T) -> T): T {
            var currentValue: T
            var newValue: T

            do {
                currentValue = atomicValue.get()
                newValue = transformer(currentValue)
            } while (!atomicValue.compareAndSet(currentValue, newValue))

            if (currentValue != newValue) {
                version.incrementAndGet()
                _flow.emit(newValue)
            }

            return newValue
        }

        override suspend fun updateIf(predicate: suspend (T) -> Boolean, newValue: T): Boolean {
            val currentValue = atomicValue.get()
            return if (predicate(currentValue) && atomicValue.compareAndSet(currentValue, newValue)) {
                if (currentValue != newValue) {
                    version.incrementAndGet()
                    _flow.emit(newValue)
                }
                true
            } else false
        }

        override suspend fun updateIf(predicate: suspend (T) -> Boolean, updater: suspend (T) -> T): Boolean {
            var currentValue: T
            var newValue: T

            do {
                currentValue = atomicValue.get()
                if (!predicate(currentValue)) return false
                newValue = updater(currentValue)
            } while (!atomicValue.compareAndSet(currentValue, newValue))

            if (currentValue != newValue) {
                version.incrementAndGet()
                _flow.emit(newValue)
            }

            return true
        }

        fun compareAndSet(expected: T, newValue: T): Boolean {
            val success = atomicValue.compareAndSet(expected, newValue)
            if (success && expected != newValue) {
                version.incrementAndGet()
                _flow.tryEmit(newValue)
            }
            return success
        }

        fun getAndSet(newValue: T): T {
            val oldValue = atomicValue.getAndSet(newValue)
            if (oldValue != newValue) {
                version.incrementAndGet()
                _flow.tryEmit(newValue)
            }
            return oldValue
        }

        suspend fun getAndUpdate(updater: suspend (T) -> T): T {
            var currentValue: T
            var newValue: T

            do {
                currentValue = atomicValue.get()
                newValue = updater(currentValue)
            } while (!atomicValue.compareAndSet(currentValue, newValue))

            if (currentValue != newValue) {
                version.incrementAndGet()
                _flow.emit(newValue)
            }

            return currentValue
        }

        suspend fun updateAndGet(updater: suspend (T) -> T): T {
            var currentValue: T
            var newValue: T

            do {
                currentValue = atomicValue.get()
                newValue = updater(currentValue)
            } while (!atomicValue.compareAndSet(currentValue, newValue))

            if (currentValue != newValue) {
                version.incrementAndGet()
                _flow.emit(newValue)
            }

            return newValue
        }

        override fun observe(
            scope: CoroutineScope,
            context: CoroutineContext,
            onEach: suspend (T) -> Unit
        ): Job = flow.onEach(onEach).launchIn(scope + context)

        override fun observeDistinct(
            scope: CoroutineScope,
            context: CoroutineContext,
            onEach: suspend (T) -> Unit
        ): Job = flow.distinctUntilChanged().onEach(onEach).launchIn(scope + context)

        override fun observeChanges(
            scope: CoroutineScope,
            context: CoroutineContext,
            onChange: suspend (old: T, new: T) -> Unit
        ): Job {
            var previousValue: T? = null
            return flow.onEach { currentValue ->
                previousValue?.let { prev ->
                    if (prev != currentValue) {
                        onChange(prev, currentValue)
                    }
                }
                previousValue = currentValue
            }.launchIn(scope + context)
        }

        fun getVersion(): Long = version.get()
        fun getChangeId(): Long = changeId.get()

        override fun toString(): String = "AtomicRef($value)"
        override fun equals(other: Any?): Boolean = other is AtomicRef<*> && value == other.value
        override fun hashCode(): Int = value?.hashCode() ?: 0
    }

    class ImmutableRef<T>(private val _value: T) : Readable<T> {
        override val value: T get() = _value
        override val flow: Flow<T> = flowOf(_value)
        override fun snapshot(): T = _value

        fun <R> map(mapper: (T) -> R): ImmutableRef<R> = ImmutableRef(mapper(_value))
        fun <R> flatMap(mapper: (T) -> ImmutableRef<R>): ImmutableRef<R> = mapper(_value)
        fun filter(predicate: (T) -> Boolean): ImmutableRef<T?> =
            ImmutableRef(if (predicate(_value)) _value else null)

        override fun toString(): String = "ImmutableRef($_value)"
        override fun equals(other: Any?): Boolean = other is ImmutableRef<*> && _value == other._value
        override fun hashCode(): Int = _value?.hashCode() ?: 0
    }

    class LazyRef<T>(
        private val initializer: suspend () -> T,
        private val scope: CoroutineScope = GlobalScope
    ) : Observable<T> {

        private val atomicRef = AtomicReference<Deferred<T>?>(null)
        private val _flow = MutableSharedFlow<T>(replay = 1)

        override val value: T
            get() = runBlocking { getValue() }

        override val flow: Flow<T>
            get() = _flow.asSharedFlow()

        override fun snapshot(): T = value

        private suspend fun getValue(): T {
            return atomicRef.get()?.await() ?: run {
                val newDeferred = scope.async { initializer() }
                if (atomicRef.compareAndSet(null, newDeferred)) {
                    val result = newDeferred.await()
                    _flow.emit(result)
                    result
                } else {
                    atomicRef.get()!!.await()
                }
            }
        }

        suspend fun refresh(): T {
            val newDeferred = scope.async { initializer() }
            atomicRef.set(newDeferred)
            val result = newDeferred.await()
            _flow.emit(result)
            return result
        }

        fun isInitialized(): Boolean = atomicRef.get()?.isCompleted == true

        override fun observe(
            scope: CoroutineScope,
            context: CoroutineContext,
            onEach: suspend (T) -> Unit
        ): Job = flow.onEach(onEach).launchIn(scope + context)

        override fun observeDistinct(
            scope: CoroutineScope,
            context: CoroutineContext,
            onEach: suspend (T) -> Unit
        ): Job = flow.distinctUntilChanged().onEach(onEach).launchIn(scope + context)

        override fun observeChanges(
            scope: CoroutineScope,
            context: CoroutineContext,
            onChange: suspend (old: T, new: T) -> Unit
        ): Job {
            var previousValue: T? = null
            return flow.onEach { currentValue ->
                previousValue?.let { prev ->
                    onChange(prev, currentValue)
                }
                previousValue = currentValue
            }.launchIn(scope + context)
        }
    }

    class ComputedRef<T, R>(
        private val sources: List<Readable<*>>,
        private val computer: suspend (List<Any?>) -> R
    ) : Observable<R> {

        private val _flow = MutableSharedFlow<R>(replay = 1)
        private val atomicValue = AtomicReference<R?>(null)
        private val computationJob = AtomicReference<Job?>(null)

        init {
            startComputation()
        }

        override val value: R get() = atomicValue.get() ?: runBlocking { computeValue() }

        override val flow: Flow<R>
            get() = _flow.asSharedFlow()

        override fun snapshot(): R = value

        private fun startComputation() {
            val job = GlobalScope.launch {
                combine(sources.map { it.flow }) { values ->
                    computer(values.toList())
                }.collect { newValue ->
                    atomicValue.set(newValue)
                    _flow.emit(newValue)
                }
            }
            computationJob.set(job)
        }

        private suspend fun computeValue(): R {
            val values = sources.map { it.snapshot() }
            return computer(values).also {
                atomicValue.set(it)
                _flow.emit(it)
            }
        }

        fun dispose() {
            computationJob.get()?.cancel()
        }

        override fun observe(
            scope: CoroutineScope,
            context: CoroutineContext,
            onEach: suspend (R) -> Unit
        ): Job = flow.onEach(onEach).launchIn(scope + context)

        override fun observeDistinct(
            scope: CoroutineScope,
            context: CoroutineContext,
            onEach: suspend (R) -> Unit
        ): Job = flow.distinctUntilChanged().onEach(onEach).launchIn(scope + context)

        override fun observeChanges(
            scope: CoroutineScope,
            context: CoroutineContext,
            onChange: suspend (old: R, new: R) -> Unit
        ): Job {
            var previousValue: R? = null
            return flow.onEach { currentValue ->
                previousValue?.let { prev ->
                    if (prev != currentValue) {
                        onChange(prev, currentValue)
                    }
                }
                previousValue = currentValue
            }.launchIn(scope + context)
        }
    }

    class MultiRef<T>(
        refs: List<Transformable<T>>,
        private val strategy: UpdateStrategy = UpdateStrategy.ALL
    ) : Transformable<List<T>>, Observable<List<T>> {

        enum class UpdateStrategy { ALL, FIRST, MAJORITY, CONSENSUS }

        private val _refs = refs.toMutableList()
        private val _flow = MutableSharedFlow<List<T>>(replay = 1)
        private val mutex = Mutex()

        init {
            updateFlow()
        }

        override var value: List<T>
            get() = _refs.map { it.value }  // getValue() 대신 value 프로퍼티 사용
            set(newValues) {
                require(newValues.size == _refs.size) { "Size mismatch" }
                _refs.zip(newValues).forEach { (ref, newValue) ->
                    ref.value = newValue  // setValue() 대신 value 프로퍼티 사용
                }
                updateFlow()
            }

        override val flow: Flow<List<T>>
            get() = _flow.asSharedFlow()

        override fun snapshot(): List<T> = _refs.map { it.snapshot() }

        override suspend fun emit(newValue: List<T>) {
            require(newValue.size == _refs.size) { "Size mismatch" }
            mutex.withLock {
                _refs.zip(newValue).forEach { (ref, value) ->
                    ref.emit(value)
                }
                _flow.emit(newValue)
            }
        }

        override fun tryEmit(newValue: List<T>): Boolean {
            if (newValue.size != _refs.size) return false
            return _refs.zip(newValue).all { (ref, value) ->
                ref.tryEmit(value)
            }.also { success ->
                if (success) updateFlow()
            }
        }

        override suspend fun transform(transformer: suspend (List<T>) -> List<T>): List<T> {
            return mutex.withLock {
                val currentValues = _refs.map { it.value }
                val newValues = transformer(currentValues)
                require(newValues.size == _refs.size) { "Size mismatch" }

                _refs.zip(newValues).forEach { (ref, value) ->
                    ref.emit(value)
                }
                _flow.emit(newValues)
                newValues
            }
        }

        override suspend fun updateIf(predicate: suspend (List<T>) -> Boolean, newValue: List<T>): Boolean {
            return mutex.withLock {
                val currentValues = _refs.map { it.value }
                if (predicate(currentValues)) {
                    emit(newValue)
                    true
                } else false
            }
        }

        override suspend fun updateIf(predicate: suspend (List<T>) -> Boolean, updater: suspend (List<T>) -> List<T>): Boolean {
            return mutex.withLock {
                val currentValues = _refs.map { it.value }
                if (predicate(currentValues)) {
                    val newValues = updater(currentValues)
                    emit(newValues)
                    true
                } else false
            }
        }

        suspend fun addRef(ref: Transformable<T>) {
            mutex.withLock {
                _refs.add(ref)
                updateFlow()
            }
        }

        suspend fun removeRef(ref: Transformable<T>) {
            mutex.withLock {
                _refs.remove(ref)
                updateFlow()
            }
        }

        suspend fun updateEach(values: List<T>) {
            require(values.size == _refs.size) { "Size mismatch" }
            emit(values)
        }

        suspend fun updateWhere(predicate: suspend (T) -> Boolean, newValue: T): Int {
            return mutex.withLock {
                var count = 0
                _refs.forEach { ref ->
                    if (ref.updateIf(predicate, newValue)) {
                        count++
                    }
                }
                if (count > 0) updateFlow()
                count
            }
        }

        private fun updateFlow() {
            _flow.tryEmit(_refs.map { it.value })
        }

        override fun observe(
            scope: CoroutineScope,
            context: CoroutineContext,
            onEach: suspend (List<T>) -> Unit
        ): Job = flow.onEach(onEach).launchIn(scope + context)

        override fun observeDistinct(
            scope: CoroutineScope,
            context: CoroutineContext,
            onEach: suspend (List<T>) -> Unit
        ): Job = flow.distinctUntilChanged().onEach(onEach).launchIn(scope + context)

        override fun observeChanges(
            scope: CoroutineScope,
            context: CoroutineContext,
            onChange: suspend (old: List<T>, new: List<T>) -> Unit
        ): Job {
            var previousValue: List<T>? = null
            return flow.onEach { currentValue ->
                previousValue?.let { prev ->
                    if (prev != currentValue) {
                        onChange(prev, currentValue)
                    }
                }
                previousValue = currentValue
            }.launchIn(scope + context)
        }

        fun size(): Int = _refs.size
        fun isEmpty(): Boolean = _refs.isEmpty()
        fun getRefs(): List<Transformable<T>> = _refs.toList()
    }

    class TimedRef<T>(
        initialValue: T,
        private val ttl: Duration,
        private val onExpire: suspend (T) -> T = { it }
    ) : AtomicRef<T>(initialValue) {

        private val createdAt = System.currentTimeMillis()
        private val timer = AtomicReference<Job?>(null)

        init {
            startTimer()
        }

        private fun startTimer() {
            val job = GlobalScope.launch {
                delay(ttl)
                val expired = onExpire(value)
                emit(expired)
            }
            timer.set(job)
        }

        override suspend fun emit(newValue: T) {
            super.emit(newValue)
            timer.get()?.cancel()
            startTimer()
        }

        fun isExpired(): Boolean = System.currentTimeMillis() - createdAt > ttl.inWholeMilliseconds

        fun getRemainingTime(): Duration {
            val elapsed = System.currentTimeMillis() - createdAt
            val remaining = (ttl.inWholeMilliseconds - elapsed).coerceAtLeast(0)
            return remaining.milliseconds
        }

        fun dispose() {
            timer.get()?.cancel()
        }
    }

    class CachedRef<K, V>(
        private val computer: suspend (K) -> V,
        private val maxSize: Int = 100,
        private val ttl: Duration = Duration.INFINITE
    ) {

        private data class CacheEntry<V>(
            val value: V,
            val timestamp: Long = System.currentTimeMillis()
        )

        private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
        private val accessOrder = AtomicReference(mutableListOf<K>())

        suspend fun get(key: K): V {
            val entry = cache[key]

            if (entry != null && !isExpired(entry)) {
                updateAccessOrder(key)
                return entry.value
            }

            val computed = computer(key)
            val newEntry = CacheEntry(computed)

            cache[key] = newEntry
            updateAccessOrder(key)
            evictIfNeeded()

            return computed
        }

        fun invalidate(key: K) {
            cache.remove(key)
            accessOrder.get().remove(key)
        }

        fun invalidateAll() {
            cache.clear()
            accessOrder.set(mutableListOf())
        }

        private fun isExpired(entry: CacheEntry<V>): Boolean {
            if (ttl == Duration.INFINITE) return false
            return System.currentTimeMillis() - entry.timestamp > ttl.inWholeMilliseconds
        }

        private fun updateAccessOrder(key: K) {
            val order = accessOrder.get()
            order.remove(key)
            order.add(key)
        }

        private fun evictIfNeeded() {
            if (cache.size > maxSize) {
                val order = accessOrder.get()
                val lru = order.removeFirstOrNull()
                lru?.let { cache.remove(it) }
            }
        }

        fun getStats(): CacheStats = CacheStats(
            size = cache.size,
            maxSize = maxSize,
            hitRate = 0.0
        )

        data class CacheStats(
            val size: Int,
            val maxSize: Int,
            val hitRate: Double
        )
    }

    class StateMachineRef<S, E>(
        initialState: S,
        private val transitions: Map<Pair<S, E>, S>
    ) : AtomicRef<S>(initialState) {

        suspend fun processEvent(event: E): Boolean {
            val currentState = value
            val nextState = transitions[currentState to event]

            return if (nextState != null) {
                emit(nextState)
                true
            } else false
        }

        fun canProcess(event: E): Boolean {
            return transitions.containsKey(value to event)
        }

        fun getAvailableEvents(): Set<E> {
            return transitions.keys.filter { it.first == value }.map { it.second }.toSet()
        }
    }

    class RefChain<T> private constructor(private val source: Flow<T>) {

        companion object {
            fun <T> from(readable: Readable<T>): RefChain<T> = RefChain(readable.flow)
        }

        fun <R> map(mapper: suspend (T) -> R): RefChain<R> =
            RefChain(source.map(mapper))

        fun filter(predicate: suspend (T) -> Boolean): RefChain<T> =
            RefChain(source.filter(predicate))

        fun distinct(): RefChain<T> =
            RefChain(source.distinctUntilChanged())

        fun debounce(timeout: Duration): RefChain<T> =
            RefChain(source.debounce(timeout))

        fun throttle(period: Duration): RefChain<T> =
            RefChain(source.sample(period))

        fun buffer(size: Int): RefChain<List<T>> = RefChain(
            source.buffer(size).scan(emptyList<T>()) { acc, value ->
                (acc + value).takeLast(size)
            }.filter { it.size == size }
        )

        fun <R> scan(initial: R, operation: suspend (R, T) -> R): RefChain<R> =
            RefChain(source.scan(initial, operation))

        fun <R> combine(other: RefChain<R>): RefChain<Pair<T, R>> =
            RefChain(source.combine(other.source) { a, b -> a to b })

        fun <R> zipWith(other: RefChain<R>): RefChain<Pair<T, R>> =
            RefChain(source.zip(other.source) { a, b -> a to b })

        fun take(count: Int): RefChain<T> =
            RefChain(source.take(count))

        fun takeWhile(predicate: suspend (T) -> Boolean): RefChain<T> =
            RefChain(source.takeWhile(predicate))

        fun onEach(action: suspend (T) -> Unit): RefChain<T> =
            RefChain(source.onEach(action))

        fun catch(handler: suspend (Throwable) -> T): RefChain<T> =
            RefChain(source.catch { emit(handler(it)) })

        fun retry(times: Int): RefChain<T> =
            RefChain(source.retry(times.toLong()))

        suspend fun collect(collector: FlowCollector<T>) = source.collect(collector)
        suspend fun toList(): List<T> = source.toList()
        suspend fun first(): T = source.first()
        suspend fun firstOrNull(): T? = source.firstOrNull()
        suspend fun last(): T = source.last()
        suspend fun single(): T = source.single()
        suspend fun count(): Int = source.count()
        suspend fun reduce(operation: suspend (T, T) -> T): T = source.reduce(operation)
        suspend fun <R> fold(initial: R, operation: suspend (R, T) -> R): R =
            source.fold(initial, operation)

        fun launchIn(scope: CoroutineScope): Job = source.launchIn(scope)

        suspend fun toAtomicRef(initialValue: T? = null): AtomicRef<T> {
            val ref = AtomicRef(initialValue ?: source.first())
            GlobalScope.launch {
                source.collect { ref.emit(it) }
            }
            return ref
        }

        fun toStateFlow(scope: CoroutineScope, initialValue: T): StateFlow<T> =
            source.stateIn(scope, SharingStarted.Eagerly, initialValue)
    }

    class ConditionalUpdate<T>(private val ref: Writable<T>) {
        suspend infix fun onlyIf(condition: suspend (T) -> Boolean): ConditionalUpdate<T> {
            return this
        }

        suspend infix fun updateTo(newValue: T) {
            ref.emit(newValue)
        }

        suspend infix fun updateWith(updater: suspend (T) -> T) {
            if (ref is Transformable<T>) {
                ref.transform(updater)
            }
        }
    }

    class TransformChain<T>(private val ref: Transformable<T>) {
        private val operations = mutableListOf<suspend (T) -> T>()

        fun then(operation: suspend (T) -> T): TransformChain<T> {
            operations.add(operation)
            return this
        }

        fun <R> map(mapper: suspend (T) -> R): TransformChain<R> {
            @Suppress("UNCHECKED_CAST")
            return TransformChain(computed(ref) { mapper(it) } as Transformable<R>)
        }

        fun filter(predicate: suspend (T) -> Boolean): TransformChain<T> {
            operations.add { value ->
                if (predicate(value)) value else ref.value
            }
            return this
        }

        suspend fun execute(): T {
            return operations.fold(ref.value) { acc, operation ->
                operation(acc)
            }.also { result ->
                ref.emit(result)
            }
        }
    }

    class ReactiveCommand<P, R>(
        private val executor: suspend (P) -> R,
        private val canExecute: suspend (P) -> Boolean = { true }
    ) {
        private val _isExecuting = AtomicRef(false)
        private val _results = MutableSharedFlow<Result<R>>()

        val isExecuting: Readable<Boolean> get() = _isExecuting
        val results: Flow<Result<R>> get() = _results

        suspend fun execute(parameter: P): Result<R> {
            if (_isExecuting.value || !canExecute(parameter)) {
                return Result.failure(IllegalStateException("Cannot execute command"))
            }

            _isExecuting.emit(true)

            return try {
                val result = executor(parameter)
                Result.success(result).also { _results.emit(it) }
            } catch (e: Exception) {
                Result.failure<R>(e).also { _results.emit(it) }
            } finally {
                _isExecuting.emit(false)
            }
        }
    }

    abstract class Store<S, A>(initialState: S) {
        private val _state = AtomicRef(initialState)
        private val _actions = MutableSharedFlow<A>()

        val state: Readable<S> get() = _state
        val actions: Flow<A> get() = _actions

        init {
            GlobalScope.launch {
                _actions.collect { action ->
                    val currentState = _state.value
                    val newState = reduce(currentState, action)
                    _state.emit(newState)
                    onStateChanged(currentState, newState, action)
                }
            }
        }

        protected abstract suspend fun reduce(state: S, action: A): S

        protected open suspend fun onStateChanged(oldState: S, newState: S, action: A) {
        }

        suspend fun dispatch(action: A) {
            _actions.emit(action)
        }
    }

    class Memoizer<K, V> {
        private val cache = ConcurrentHashMap<K, V>()

        suspend fun memoize(key: K, computer: suspend (K) -> V): V {
            return cache.getOrPut(key) { runBlocking { computer(key) } }
        }

        fun invalidate(key: K) = cache.remove(key)
        fun clear() = cache.clear()
        fun size() = cache.size
    }

    class MockRef<T>(initialValue: T) : AtomicRef<T>(initialValue) {
        private val _emissions = mutableListOf<T>()

        val emissions: List<T> get() = _emissions.toList()

        override suspend fun emit(newValue: T) {
            _emissions.add(newValue)
            super.emit(newValue)
        }

        fun clearEmissions() = _emissions.clear()

        fun getEmissionCount() = _emissions.size

        fun getLastEmission(): T? = _emissions.lastOrNull()
    }

    class TestScheduler {
        private var currentTime = 0L
        private val scheduledTasks = mutableListOf<ScheduledTask>()

        data class ScheduledTask(
            val time: Long,
            val task: suspend () -> Unit
        )

        fun schedule(delay: Duration, task: suspend () -> Unit) {
            scheduledTasks.add(ScheduledTask(currentTime + delay.inWholeMilliseconds, task))
        }

        suspend fun advanceTimeBy(duration: Duration) {
            val targetTime = currentTime + duration.inWholeMilliseconds

            while (currentTime < targetTime) {
                val nextTask = scheduledTasks
                    .filter { it.time <= targetTime }
                    .minByOrNull { it.time }

                if (nextTask != null) {
                    currentTime = nextTask.time
                    nextTask.task()
                    scheduledTasks.remove(nextTask)
                } else {
                    currentTime = targetTime
                }
            }
        }

        fun getCurrentTime(): Long = currentTime

        fun <T> Observable<T>.onChange(
            scope: CoroutineScope,
            context: CoroutineContext = Dispatchers.Default,
            callback: suspend (old: T, new: T) -> Unit
        ): Job {
            return this.observeChanges(scope, context, callback)
        }

        class BypassConditionalRef<T>(val ref: Transformable<T>, val condition: suspend (T) -> Boolean)
        class BypassTransformRef<T>(val ref: Writable<T>, val transform: suspend (T) -> T)
        class BypassChainRef<T>(val target: Writable<T>, val operations: List<suspend (T) -> T>)



    }

    class RefMetrics<T>(private val ref: Readable<T>) {
        private val _updateCount = AtomicLong(0)
        private val _averageUpdateTime = AtomicReference(0.0)
        private val _lastUpdateTime = AtomicLong(0)

        private val updateCount: Long get() = _updateCount.get()
        private val averageUpdateTime: Double get() = _averageUpdateTime.get()
        private val lastUpdateTime: Long get() = _lastUpdateTime.get()

        suspend fun startMonitoring(scope: CoroutineScope) {
            ref.flow.onEach { _ ->
                val currentTime = System.currentTimeMillis()
                _updateCount.incrementAndGet()
                _lastUpdateTime.set(currentTime)

                val count = _updateCount.get()
                val current = _averageUpdateTime.get()
                val newAverage = ((current * (count - 1)) + currentTime) / count
                _averageUpdateTime.set(newAverage)

            }.launchIn(scope)
        }

        fun reset() {
            _updateCount.set(0)
            _averageUpdateTime.set(0.0)
            _lastUpdateTime.set(0)
        }

        fun getMetricsSnapshot(): MetricsSnapshot = MetricsSnapshot(
            updateCount = updateCount,
            averageUpdateTime = averageUpdateTime,
            lastUpdateTime = lastUpdateTime,
            currentValue = ref.value.toString()
        )
    }

    data class MetricsSnapshot(
        val updateCount: Long,
        val averageUpdateTime: Double,
        val lastUpdateTime: Long,
        val currentValue: String
    )
}

suspend infix fun <T> T.bypass(target: Ref.Writable<T>) {
    target.emit(this)
}

suspend infix fun <T> T.bypass(conditional: Ref.BypassConditionalRef<T>) {
    conditional.ref.updateIf(conditional.condition, this)
}

suspend infix fun <T> T.bypass(transformer: Ref.TransformRef<T>) {
    val transformedValue = transformer.transform(this)
    transformer.ref.emit(transformedValue)
}

suspend infix fun <T> T.bypass(chain: Ref.ChainRef<T>) {
    var currentValue = this
    for (operation in chain.operations) {
        currentValue = operation(currentValue)
    }
    chain.target.emit(currentValue)
}

infix fun <T> T.bypass(target: Ref.ImmutableRef<T>): Ref.ImmutableRef<T> {
    return Ref.ImmutableRef(this)
}

suspend infix fun <T> Ref.Writable<T>.onlyIf(condition: suspend (T) -> Boolean): Ref.BypassConditionalRef<T> {
    require(this is Ref.Transformable<T>) { "onlyIf can only be used with Transformable refs" }
    return Ref.BypassConditionalRef(this, condition)
}

suspend infix fun <T> Ref.Writable<T>.withTransform(transform: suspend (T) -> T) =
    Ref.TransformRef(this, transform)

suspend infix fun <T> Ref.Writable<T>.withChain(operations: List<suspend (T) -> T>) =
    Ref.ChainRef(this, operations)

fun <T> atomicRefOf(value: T): Ref.AtomicRef<T> = Ref.AtomicRef(value)

fun <T> immutableRefOf(value: T): Ref.ImmutableRef<T> = Ref.ImmutableRef(value)

fun <T> lazyRefOf(
    scope: CoroutineScope = GlobalScope,
    initializer: suspend () -> T
): Ref.LazyRef<T> = Ref.LazyRef(initializer, scope)

fun <T> multiRefOf(vararg refs: Ref.Transformable<T>): Ref.MultiRef<T> =
    Ref.MultiRef(refs.toList())

fun <T> timedRefOf(
    value: T,
    ttl: Duration,
    onExpire: suspend (T) -> T = { it }
): Ref.TimedRef<T> = Ref.TimedRef(value, ttl, onExpire)

fun <K, V> cachedRefOf(
    maxSize: Int = 100,
    ttl: Duration = Duration.INFINITE,
    computer: suspend (K) -> V
): Ref.CachedRef<K, V> = Ref.CachedRef(computer, maxSize, ttl)

fun <S, E> stateMachineRefOf(
    initialState: S,
    transitions: Map<Pair<S, E>, S>
): Ref.StateMachineRef<S, E> = Ref.StateMachineRef(initialState, transitions)

fun <T1, R> computed(
    ref1: Ref.Readable<T1>,
    computer: suspend (T1) -> R
): Ref.ComputedRef<T1, R> = Ref.ComputedRef(listOf(ref1)) { values ->
    @Suppress("UNCHECKED_CAST")
    computer(values[0] as T1)
}

fun <T1, T2, R> computed(
    ref1: Ref.Readable<T1>,
    ref2: Ref.Readable<T2>,
    computer: suspend (T1, T2) -> R
): Ref.ComputedRef<Pair<T1, T2>, R> = Ref.ComputedRef(listOf(ref1, ref2)) { values ->
    @Suppress("UNCHECKED_CAST")
    computer(values[0] as T1, values[1] as T2)
}

fun <T1, T2, T3, R> computed(
    ref1: Ref.Readable<T1>,
    ref2: Ref.Readable<T2>,
    ref3: Ref.Readable<T3>,
    computer: suspend (T1, T2, T3) -> R
): Ref.ComputedRef<Triple<T1, T2, T3>, R> = Ref.ComputedRef(listOf(ref1, ref2, ref3)) { values ->
    @Suppress("UNCHECKED_CAST")
    computer(values[0] as T1, values[1] as T2, values[2] as T3)
}

operator fun <T> Ref.Readable<T>.plus(other: Ref.Readable<T>): Ref.MultiRef<T> {
    return when {
        this is Ref.Transformable<T> && other is Ref.Transformable<T> ->
            multiRefOf(this, other)
        else -> throw IllegalArgumentException("Both references must be transformable")
    }
}

operator fun <T> Ref.MultiRef<T>.plus(other: Ref.Transformable<T>): Ref.MultiRef<T> {
    return Ref.MultiRef(this.getRefs() + other)
}

fun <T, R> Ref.Readable<T>.map(mapper: suspend (T) -> R): Ref.ComputedRef<T, R> =
    computed(this, mapper)

fun <T> Ref.Readable<T>.filter(predicate: suspend (T) -> Boolean): Ref.ComputedRef<T, T?> =
    computed(this) { value -> if (predicate(value)) value else null }

fun <T, R> Ref.Readable<T>.flatMap(mapper: suspend (T) -> Ref.Readable<R>): Ref.ComputedRef<T, R> =
    computed(this) { value -> mapper(value).value }

fun <T> Ref.Readable<T>.chain(): Ref.RefChain<T> = Ref.RefChain.from(this)

suspend fun <T> Ref.Writable<T>.updateIf(condition: suspend (T) -> Boolean): Ref.ConditionalUpdate<T> =
    Ref.ConditionalUpdate(this)

fun <T> Ref.Transformable<T>.transform(): Ref.TransformChain<T> = Ref.TransformChain(this)

fun <T> Ref.Readable<T>.buffered(size: Int = Channel.UNLIMITED): Flow<T> =
    flow.buffer(size)

fun <T> Ref.Readable<T>.conflated(): Flow<T> = flow.conflate()

fun <T> Ref.Readable<T>.debounce(timeout: Duration): Flow<T> = flow.debounce(timeout)
fun <T> Ref.Readable<T>.sample(period: Duration): Flow<T> = flow.sample(period)
fun <T> Ref.Readable<T>.throttleFirst(windowDuration: Duration): Flow<T> =
    flow.transformLatest { value ->
        emit(value)
        delay(windowDuration)
    }

fun <T> Ref.Readable<T>.catchAndRecover(recovery: suspend (Throwable) -> T): Flow<T> =
    flow.catch { emit(recovery(it)) }

fun <T> Ref.Readable<T>.retryWithBackoff(
    times: Int,
    initialDelay: Duration = 1.seconds,
    factor: Double = 2.0
): Flow<T> = flow.retryWhen { _, attempt ->
    if (attempt < times) {
        delay((initialDelay.inWholeMilliseconds * factor.pow(attempt.toDouble())).toLong())
        true
    } else false
}

fun <T> Ref.Readable<T>.debug(tag: String = "FlowX"): Flow<T> =
    flow.onEach { value -> println("[$tag] $value") }

fun <T> Ref.Readable<T>.logChanges(tag: String = "FlowX"): Flow<T> {
    var previous: T? = null
    return flow.onEach { current ->
        previous?.let { prev ->
            println("[$tag] $prev -> $current")
        }
        previous = current
    }
}

fun <T> Ref.Observable<T>.onChange(
    scope: CoroutineScope,
    context: CoroutineContext = Dispatchers.Default,
    callback: suspend (old: T, new: T) -> Unit
): Job {
    return this.observeChanges(scope, context, callback)
}

fun <T> Ref.Observable<T>.onChange(
    context: CoroutineContext = Dispatchers.Default,
    callback: suspend (old: T, new: T) -> Unit
): Job {
    return this.observeChanges(GlobalScope, context, callback)
}

fun <T> Ref.Observable<List<T>>.onMultiChange(
    scope: CoroutineScope,
    callback: suspend (old: List<T>, new: List<T>) -> Unit
): Job {
    return this.onChange(scope, callback = callback)
}

fun <T> Ref.Observable<T>.syncTo(
    targetRef: Ref.Writable<T>,
    scope: CoroutineScope
): Job {
    return this.onChange(scope) { _, newValue ->
        newValue bypass targetRef
    }
}

enum class State { IDLE, LOADING, SUCCESS, ERROR }
enum class Event { START, COMPLETE, FAIL, RESET }
suspend fun demo() {
    println("=== FlowX Ultimate Reactive Reference System Demo ===\n")

    println("1. Basic AtomicRef Usage:")
    val counter = atomicRefOf(0)
    counter.observe(GlobalScope) { value ->
        println("Counter changed to: $value")
    }

    counter.emit(10)
    counter.transform { it + 5 }
    println("Final counter value: ${counter.value}\n")

    println("2. Computed References:")
    val a = atomicRefOf(10)
    val b = atomicRefOf(20)
    val sum = computed(a, b) { x, y -> x + y }

    sum.observe(GlobalScope) { result ->
        println("Sum: $result")
    }

    a.emit(15)
    delay(100)

    println("\n3. Chain Operations:")
    val numbers = atomicRefOf(listOf(1, 2, 3, 4, 5))

    numbers.chain()
        .map { list -> list.map { it * 2 } }
        .filter { list -> list.size > 3 }
        .debounce(100.milliseconds)
        .onEach { result -> println("Processed list: $result") }
        .launchIn(GlobalScope)

    numbers.emit(listOf(1, 2, 3, 4, 5, 6))
    delay(200)

    println("\n4. State Machine:")

    val stateMachine = stateMachineRefOf(
        State.IDLE,
        mapOf(
            (State.IDLE to Event.START) to State.LOADING,
            (State.LOADING to Event.COMPLETE) to State.SUCCESS,
            (State.LOADING to Event.FAIL) to State.ERROR,
            (State.SUCCESS to Event.RESET) to State.IDLE,
            (State.ERROR to Event.RESET) to State.IDLE
        )
    )

    stateMachine.observe(GlobalScope) { state ->
        println("State: $state")
    }

    stateMachine.processEvent(Event.START)
    delay(50)
    stateMachine.processEvent(Event.COMPLETE)
    delay(50)

    println("\n5. Timed Reference:")
    val timedValue = timedRefOf(
        "Initial",
        2.seconds
    ) { expired ->
        println("Value expired: $expired")
        "Expired!"
    }

    timedValue.observe(GlobalScope) { value ->
        println("Timed value: $value")
    }

    delay(100)
    timedValue.emit("Updated")

    println("\n6. Cached Reference:")
    val expensiveCache = cachedRefOf<String, String>(
        maxSize = 10,
        ttl = 5.seconds
    ) { key ->
        delay(100)
        "Computed: $key"
    }

    println("Cache result: ${expensiveCache.get("test1")}")
    println("Cache result (cached): ${expensiveCache.get("test1")}")

    println("\n7. Reactive Command:")
    val saveCommand = Ref.ReactiveCommand<String, String>(
        executor = { data ->
            delay(500)
            "Saved: $data"
        },
        canExecute = { data -> data.isNotBlank() }
    )

    saveCommand.results.onEach { result ->
        println("Command result: $result")
    }.launchIn(GlobalScope)

    saveCommand.execute("Important Data")

    println("\n8. Performance Monitoring:")
    val monitoredRef = atomicRefOf("test")
    val metrics = Ref.RefMetrics(monitoredRef)
    metrics.startMonitoring(GlobalScope)

    repeat(5) {
        monitoredRef.emit("test$it")
        delay(50)
    }

    delay(100)
    println("Metrics: ${metrics.getMetricsSnapshot()}")

    println("\n=== Demo completed ===")
}

