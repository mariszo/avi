(ns avi.pervasive
  (:require [avi.assert :refer :all]))

(defn- subcollection
  [collection & rest-of-args]
  (if (string? collection)
    (apply subs collection rest-of-args)
    (apply subvec collection rest-of-args)))

(def ^:private vector-concatenate
  (comp vec concat))



(defn splice
  "Replace or delete elements starting at the splice-start, up to but not
  including splice-end.

  It is not an error for end to be well past the end of the collection."
  {:test (examples
           (example (= "xa" (splice "a" 0 0 "x")))
           (example (= "a" (splice "a" 1 42)))
           (example (= [1] (splice [1] 1 42)))
           (example (= "xxaxx" (splice "xxxxxx" 2 4 "a"))))}
  ([collection splice-start splice-end]
   (splice collection splice-start splice-end (empty collection)))
  ([collection splice-start splice-end replacements]
   (let [concatenate (if (string? collection) str vector-concatenate)
         first-section (subcollection collection 0 splice-start)
         last-section (if (>= splice-end (count collection))
                        (empty collection)
                        (subcollection collection splice-end))]
     (concatenate
       first-section
       replacements
       last-section))))

(defn n-times
  [thing n a-fn]
  (reduce
    (fn [thing n]
      (a-fn thing))
    thing
    (range n)))

(defn fail
  [& args]
  (let [options (into #{} args)
        beep? (:beep options)
        message (first (filter string? args))
        ex-map (cond-> {}
                 beep? (assoc :beep? true)
                 message (assoc :message [:white :red message]))]
    (throw (ex-info (or message "") ex-map))))
