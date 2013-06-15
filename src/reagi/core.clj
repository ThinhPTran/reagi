(ns reagi.core
  (:require [clojure.core :as core])
  (:refer-clojure :exclude [constantly derive mapcat map filter remove
                            merge reduce cycle count dosync]))

(def ^:dynamic *cache* nil)

(defmacro dosync
  "Any event stream or behavior deref'ed within this block is guaranteed to
  always return the same value."
  [& body]
  `(binding [*cache* (atom {})]
     ~@body))

(defn- cache-hit [cache key get-value]
  (if (contains? cache key)
    cache
    (assoc cache key (get-value))))

(defn- cache-lookup! [cache key get-value]
  (-> (swap! cache cache-hit key get-value)
      (get key)))

(defn behavior-call
  "Takes a zero-argument function and yields a Behavior object that will
  evaluate the function each time it is dereferenced. See: behavior."
  [func]
  (reify
    clojure.lang.IDeref
    (deref [behavior]
      (if *cache*
        (cache-lookup! *cache* behavior func)
        (func)))))

(defmacro behavior
  "Takes a body of expressions and yields a Behavior object that will evaluate
  the body each time it is dereferenced. All derefs of behaviors that happen
  inside a containing behavior will be consistent."
  [& form]
  `(behavior-call (fn [] ~@form)))

(defprotocol Observable
  (subscribe [stream observer] "Add an observer function to the stream."))

(defn- weak-hash-map []
  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))

(defn event-stream
  "Create a new event stream with an optional initial value. Calling deref on
  an event stream will return the last value pushed into the event stream, or
  the init value if no values have been pushed."
  ([] (event-stream nil))
  ([init]
     (let [observers (weak-hash-map)
           head      (atom init)]
       (reify
         clojure.lang.IDeref
         (deref [stream]
           (if *cache*
             (cache-lookup! *cache* stream #(deref head))
             (deref head)))
         clojure.lang.IFn
         (invoke [stream msg]
           (reset! head msg)
           (doseq [[observer _] observers]
             (observer msg)))
         Observable
         (subscribe [stream observer]
           (.put observers observer true))))))

(defn push!
  "Push one or more messages onto the stream."
  ([stream])
  ([stream msg]
     (stream msg))
  ([stream msg & msgs]
     (doseq [m (cons msg msgs)]
       (stream m))))

(defn freeze
  "Return a stream that can no longer be pushed to."
  ([stream] (freeze stream nil))
  ([stream parent-refs]
     (reify
       clojure.lang.IDeref
       (deref [_] parent-refs @stream)
       Observable
       (subscribe [_ observer] (subscribe stream observer)))))

(defn frozen?
  "Returns true if the stream cannot be pushed to."
  [stream]
  (not (ifn? stream)))

(defn- derived-stream
  "Derive an event stream from a function."
  [func init]
  (let [stream (event-stream init)]
    (reify
      clojure.lang.IDeref
      (deref [_] @stream)
      clojure.lang.IFn
      (invoke [_ msg] (func stream msg))
      Observable
      (subscribe [_ observer] (subscribe stream observer)))))

(defn derive
  "Derive a new event stream from another and a function. The function will be
  called each time the existing stream receives a message, and will have the
  new stream and the message as arguments."
  ([func stream]
     (derive func nil stream))
  ([func init stream]
     (let [stream* (derived-stream func init)]
       (subscribe stream stream*)
       (freeze stream* [stream]))))

(defn mapcat
  "Mapcat a function over a stream."
  ([f stream]
     (mapcat f nil stream))
  ([f init stream]
     (derive #(apply push! %1 (f %2)) init stream)))

(defn map
  "Map a function over a stream."
  ([f stream]
     (map f nil stream))
  ([f init stream]
     (mapcat #(list (f %)) init stream)))

(defn filter
  "Filter a stream by a predicate."
  ([pred stream]
     (filter pred nil stream))
  ([pred init stream]
     (mapcat #(if (pred %) (list %)) init stream)))

(defn remove
  "Remove all items in a stream the predicate does not match."
  ([pred stream]
     (remove pred nil stream))
  ([pred init stream]
     (filter (complement pred) init stream)))

(defn filter-by
  "Filter a stream by matching part of a map against a message."
  ([partial stream]
     (filter-by partial nil stream))
  ([partial init stream]
     (filter #(= % (core/merge % partial)) init stream)))

(defn merge
  "Merge multiple streams into one."
  [& streams]
  (let [stream* (event-stream)]
    (doseq [stream streams]
      (subscribe stream stream*))
    (freeze stream* streams)))

(defn reduce
  "Reduce a stream with a function."
  ([f stream]
     (reduce f nil stream))
  ([f init stream]
     (let [acc (atom init)]
       (derive #(push! %1 (swap! acc f %2)) init stream))))

(defn count
  "Return an accumulating count of the items in a stream."
  [stream]
  (reduce (fn [x _] (inc x)) 0 stream))

(defn accum
  "Change an initial value based on an event stream of functions."
  ([stream]
     (accum nil stream))
  ([init stream]
      (reduce #(%2 %1) init stream)))

(defn uniq
  "Remove any successive duplicates from the stream."
  ([stream]
     (uniq nil stream))
  ([init stream]
     (->> stream
          (reduce #(if (= (peek %1) %2) (conj %1 %2) [%2]) [])
          (filter #(= (core/count %) 1))
          (map first init))))

(defn cycle
  "Incoming events cycle a sequence of values. Useful for switching between
  states."
  [values stream]
  (let [vs (atom (core/cycle values))]
    (map (fn [_] (first (swap! vs next)))
         (first values)
         stream)))

(defn constantly
  "Constantly map the same value over an event stream."
  [value stream]
  (map (core/constantly value) value stream))

(defn throttle
  "Remove any events in a stream that occur too soon after the prior event.
  The timeout is specified in milliseconds."
  ([timeout-ms stream]
     (throttle timeout-ms nil stream))
  ([timeout-ms init stream]
     (->> stream
          (map (fn [x] [(System/currentTimeMillis) x]))
          (reduce (fn [[t0 _] [t1 x]] [(- t1 t0) x]) [0 nil])
          (remove (fn [[dt _]] (>= timeout-ms dt)))
          (map second init))))
