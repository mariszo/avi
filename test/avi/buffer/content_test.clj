(ns avi.buffer.content-test
  (:require [avi.buffer.content :as c]
            [clojure.string :as string]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [com.gfredericks.test.chuck.generators :as gen']
            [com.gfredericks.test.chuck.properties :as prop']
            [midje.sweet :refer :all]
            [schema.core :as s]))

(s/set-fn-validation! true)

(facts "about buffer contents"
  (fact "we can retrieve buffer contents initial text"
    (:lines (c/content "Hello, World!")) => ["Hello, World!"]
    (:lines (c/content "Line 1\nLine 2")) => ["Line 1" "Line 2"]
    (:lines (c/content "Line 1\nLine 3\n")) => ["Line 1" "Line 3"]
    (:lines (c/content "Line 1\n\nLine 3")) => ["Line 1" "" "Line 3"])
  (fact "we always have at least one line"
    (:lines (c/content "")) => [""])
  (fact "if the last character is a newline, it does not make an extra line"
    (:lines (c/content "\n")) => [""]
    (:lines (c/content "\n\n")) => ["" ""]
    (:lines (c/content "\nfoo")) => ["" "foo"]
    (:lines (c/content "foo\n")) => ["foo"]))

(facts "about replacing contents"
  (fact "replace can insert at beginning of buffer"
    (:lines (c/replace (c/content "Hello!") [1 0] [1 0] "xyz")) => ["xyzHello!"])
  (fact "replace can insert within a line"
    (:lines (c/replace (c/content "Hello!") [1 2] [1 2] "//")) => ["He//llo!"])
  (fact "replace can insert at the end of a line"
    (:lines (c/replace (c/content "Hello!") [1 6] [1 6] "//")) => ["Hello!//"]))

(def text-generator
  (gen/fmap (partial string/join "\n") (gen/vector gen/string-ascii)))

(def content-generator
  (gen/fmap c/content text-generator))

(defn mark-generator
  [{:keys [lines]}]
  (gen'/for [line (gen/choose 1 (count lines))
             column (gen/choose 0 (count (get lines (dec line))))]
    [line column]))

(defn start-end-mark-generator
  [content]
  (gen/fmap sort (gen/vector (mark-generator content) 2)))

(def replace-generator
  (gen'/for [content content-generator
             [start end] (start-end-mark-generator content)
             replacement text-generator]
    {:replacement replacement
     :start start
     :end end
     :pre-content content
     :post-content (c/replace content start end replacement)}))

(defspec replace-does-not-change-lines-prior-to-first-mark 25
  (prop/for-all [{:keys [pre-content post-content] [start-line] :start} replace-generator]
    (every?
      #(= (get-in pre-content [:lines (dec %)]) (get-in post-content [:lines (dec %)]))
      (range 1 start-line))))
