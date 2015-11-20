(ns avi.normal-mode
  (:require [packthread.core :refer :all]
            [avi.beep :as beep]
            [avi.brackets :as brackets]
            [avi.buffer :as b]
            [avi.command-line-mode :as command-line-mode]
            [avi.editor :as e]
            [avi.eventmap :as em]
            [avi.insert-mode :as insert-mode]
            [avi.pervasive :refer :all]
            [avi.search]))

(defn- change-column
  [editor j-fn]
  (+> editor
      (let [{[i j] :point, :as buffer} (e/current-buffer editor)
        j (j-fn j)
        new-position [i j]]
        (if (b/point-can-move-to-column? buffer j)
          (in e/current-buffer
              (b/move-point [:goto new-position]))
          beep/beep))))

(defn- current-line 
  [editor] 
  (let [buffer (e/current-buffer editor)
        [row] (:point buffer)]
    (b/line buffer row)))

(defn- scroll
  [editor update-fn]
  (+> editor
    (in e/current-buffer
        (b/scroll update-fn))))

(def motions
  '{"0"  [:goto [:current 0]]
    "^"  [:goto [:current :first-non-blank]]
    "$"  [:goto [:current :end-of-line]]
    "gg" [:goto [(?line 0) :first-non-blank]]
    "G"  [:goto [(?line :last) :first-non-blank]]
    "H"  [:goto [[:viewport-top (?line 0)] :last-explicit]]
    "M"  [:goto [:viewport-middle :last-explicit]]})

(defn variable?
  [a]
  (and (symbol? a)
       (= (get (name a) 0) \?)))

(defn variables
  [pattern]
  (->> pattern flatten (filter variable?) (into #{})))

(defn substitute
  [a bindings]
  (cond
    (variable? a)
    (bindings a)

    (and (list? a) (variable? (first a)))
    (if-let [value (bindings (first a))]
      value
      (second a))

    (coll? a)
    (into (empty a) (map #(substitute % bindings) a))

    :else
    a))

(defn make-move-motion
  [pattern]
  (let [vs (variables pattern)]
    (with-meta
      (fn+> [editor _]
        (let [bindings (fn [name]
                         (when (= '?line name)
                           (some-> (:count editor) dec)))
              motion (substitute pattern bindings)]
          (in e/current-buffer
            (b/move-point motion))))
      (if (vs '?count)
        {:no-repeat true}))))

(def top-level-motions
  (->> motions
    (map (juxt first (comp make-move-motion second)))
    (into {})))

(def wrap-normal-mode
  (em/eventmap
    (merge
      top-level-motions
      {"dd" ^:no-repeat (fn+> [editor _]
                          (let [repeat-count (:count editor)]
                            (in e/current-buffer
                                b/start-transaction
                                (n-times (or repeat-count 1) b/delete-current-line)
                                b/commit)))

       "f<.>" (fn+> [editor [_ key-name]]
                (let [ch (get key-name 0)]
                  (in e/current-buffer
                    (b/move-point [:goto [:current [:to-next ch]]]))))

       "h" (fn+> [editor _]
             (change-column dec))

       "j" (fn+> [editor _]
             (e/change-line inc))

       "k" (fn+> [editor _]
             (e/change-line dec))

       "l" (fn+> [editor _]
             (change-column inc))

       "t<.>" (fn+> [editor [_ key-name]]
                (let [ch (get key-name 0)]
                  (in e/current-buffer
                    (b/move-point [:goto [:current [:before-next ch]]]))))

       "u" (fn+> [editor _]
             (in e/current-buffer
               b/undo))

       "x" ^:no-repeat (fn+> [editor _]
                         (let [repeat-count (:count editor)]
                           (in e/current-buffer
                               b/start-transaction
                               (as-> buffer
                                 (reduce
                                   (fn [buffer n]
                                     (b/delete-char-under-point buffer))
                                   buffer
                                   (range (or repeat-count 1))))
                               b/commit)))

       "J" ^:no-repeat (fn+> [editor _]
                         (let [{[i j] :point, lines :lines} (e/current-buffer editor)
                               n (or (:count editor) 1)
                               new-line (reduce
                                          #(str %1 " " %2)
                                          (subvec lines i (+ i n 1)))
                               new-lines (splice lines i (+ i n 1) [new-line])]
                           (in e/current-buffer
                               b/start-transaction
                               (assoc :lines new-lines)
                               b/commit)))

       "L" ^:no-repeat (fn+> [editor event]
                         (let [count (dec (or (:count editor) 1))]
                           (in e/current-buffer
                             (b/move-point [:goto [[:viewport-bottom count] :last-explicit]]))))

       "<C-D>" (fn+> [editor _]
                 (let [buffer (e/current-buffer editor)]
                   (if (b/on-last-line? buffer)
                     beep/beep
                     (in e/current-buffer
                       (b/move-and-scroll-half-page :down)))))

       "<C-E>" (fn+> [editor _]
                 (scroll inc))

       "<C-R>" (fn+> [editor _]
                 (in e/current-buffer
                   b/redo))

       "<C-U>" (fn+> [editor _]
                 (let [buffer (e/current-buffer editor)
                       [i] (:point buffer)]
                   (if (zero? i)
                     beep/beep
                     (in e/current-buffer
                       (b/move-and-scroll-half-page :up)))))

       "<C-Y>" (fn+> [editor _]
                 (scroll dec))})))

(defn- update-count
  [editor digit]
  (let [old-count (or (:count editor) 0)
        new-count (+ (* 10 old-count) digit)]
    (assoc editor :count new-count)))

(defn- wrap-collect-repeat-count
  [responder]
  (fn+> [editor [event-type event-data :as event]]
    (if (seq (:pending-events editor))
      (responder event)
      (cond
        (= event [:keystroke "0"])
        (if (:count editor)
          (update-count 0)
          (responder event))

        (and (= 1 (count event-data))
             (Character/isDigit (get event-data 0)))
        (update-count (Integer/parseInt event-data))

        :else
        (responder event)))))

(def responder
  (-> beep/beep-responder
      wrap-normal-mode
      avi.search/wrap-normal-search-commands
      command-line-mode/wrap-enter-command-line-mode
      insert-mode/wrap-enter-insert-mode
      brackets/wrap-go-to-matching-bracket
      wrap-collect-repeat-count))

(def wrap-mode (e/mode-middleware :normal responder))
