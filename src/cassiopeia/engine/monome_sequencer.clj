(ns cassiopeia.engine.monome-sequencer
  (:use [overtone.core]
        [overtone.helpers.lib :only [uuid]])
  (:require [monome.fonome :as fon]
            [mud.timing :as time]
            [cassiopeia.engine.sequencer :as seq]))

(defonce m-sequencers (atom {}))

(defn- get-row [grid y range-x]
  (let [row (for [x (range range-x)]
              (get grid [x y] false))]
    (map #(if % 1 0) row)))

(defn sequencer-write-row! [sequencer y range-x grid]
  (let [row (get-row grid y range-x)]
    (when-not (>= y (:num-samples sequencer))
      (seq/sequencer-write! sequencer y row))))

(defn sequencer-write-grid! [sequencer range-x range-y grid]
  (doseq [y (range range-x)]
    (sequencer-write-row! sequencer y range-x grid)))

(defn monome-sequencer?
  [o]
  (isa? (type o) ::monome-sequencer))

(defn- update-monome-leds
  [tgt-fonome range-x beat]
  (let [beat-track-y (dec (:height tgt-fonome))]
    (fon/led-on tgt-fonome  (mod (dec beat) range-x) beat-track-y)
    (fon/led-off tgt-fonome  (mod beat range-x) beat-track-y)))

(defn mk-ticker
  ([tgt-fonome handle] (mk-ticker tgt-fonome handle (atom time/main-beat) (uuid) (:width tgt-fonome)))
  ([tgt-fonome handle beat-bus-a beat-key range-x]
     (let [t-id (:trig-id @beat-bus-a)
           key3 (uuid)]
       (on-trigger t-id
                   (fn [beat]
                     (let [beat-track-y (dec (:height tgt-fonome))
                           total-cells (* (:height tgt-fonome) range-x)
                           x-pos (mod beat range-x)
                           y-pos (/ (mod beat 96) range-x)]
                       (when (and (= 0.0 x-pos) (= 0.0 y-pos))
                         (doseq [x (range total-cells)]
                           (fon/led-off tgt-fonome (mod x range-x) (/ x range-x))))
                       (fon/led-on tgt-fonome x-pos y-pos)))
                   key3)

       (with-meta
         {:trg-key key3
          :beat-key       beat-key
          :fonome         tgt-fonome
          :handle         handle
          :status         (atom :running)}
         {:type ::monome-ticker}))))

(defn mk-monome-sequencer
  ([nk-group handle samples tgt-fonome]
     (mk-monome-sequencer nk-group handle samples tgt-fonome 0))
  ([nk-group handle samples tgt-fonome out-bus]
     (mk-monome-sequencer nk-group handle samples tgt-fonome 0 (foundation-default-group)))
  ([nk-group handle samples tgt-fonome out-bus tgt-g]
     (mk-monome-sequencer nk-group handle samples tgt-fonome out-bus tgt-g true))
  ([nk-group handle samples tgt-fonome out-bus tgt-g with-mixers?]
     (mk-monome-sequencer nk-group handle samples tgt-fonome out-bus tgt-g with-mixers? time/main-beat))
  ([nk-group handle samples tgt-fonome out-bus tgt-g with-mixers? beat-bus]
     (when-not tgt-fonome
       (throw (IllegalArgumentException. "Please pass a valid fonome to mk-monome-sequencer")))

     (let [range-x     (:width tgt-fonome)
           range-y     (:height tgt-fonome)
           t-id        (:trig-id beat-bus)
           beat-bus-a  (atom beat-bus)
           sequencer   (seq/mk-sequencer nk-group
                                         handle
                                         (take (dec range-y) samples)
                                         range-x
                                         tgt-g
                                         beat-bus-a
                                         out-bus)
           seq-atom    (atom sequencer)
           key1        (uuid)
           key2        (uuid)
           key3        (uuid)
           m-sequencer (with-meta
                         {:sequencer      seq-atom
                          :led-change-key key1
                          :press-key      key2
                          :beat-key       key3
                          :fonome         tgt-fonome
                          :handle         handle
                          :status         (atom :running)
                          :nk-group       nk-group}
                         {:type ::monome-sequencer})]

       (swap! m-sequencers (fn [ms]
                             (when (contains? ms handle)
                               (seq/sequencer-kill sequencer)
                               (throw (IllegalArgumentException.
                                       (str "A monome-sequencer with handle "
                                            handle
                                            " already exists."))))
                             (assoc ms handle m-sequencer)))

       (sequencer-write-grid! sequencer range-x range-y (fon/led-state tgt-fonome))

       (on-event [:fonome :led-change (:id tgt-fonome)]
                 (fn [{:keys [new-leds y]}]
                   (if y
                     (sequencer-write-row! @seq-atom y range-x new-leds)
                     (sequencer-write-grid! @seq-atom range-x range-y new-leds)))
                 key1)

       (on-event [:fonome :press (:id tgt-fonome)]
                 (fn [{:keys [x y fonome]}]
                   (fon/toggle-led fonome x y))
                 key2)

       (mk-ticker tgt-fonome ::ticker128 beat-bus-a key3 range-x)

       (oneshot-event :reset (fn [_] (remove-event-handler key1) (remove-event-handler key2)) (uuid))

       m-sequencer)))

(defn running? [seq]
  (= :running @(:status seq)))

(defn stop-sequencer [seq]
  (assert (monome-sequencer? seq))
  (when (running? seq)
    (reset! (:status seq) :stopped)
    (swap! m-sequencers dissoc (:handle seq))
    (seq/sequencer-kill @(:sequencer seq))
    (remove-event-handler (:led-change-key seq))
    (remove-event-handler (:press-key seq))
    (remove-event-handler (:beat-key seq))))

(defn swap-samples! [m-seq samples]
  (assert (monome-sequencer? m-seq))
  (seq/swap-samples! @(:sequencer m-seq) samples))

(defn swap-beat-bus! [m-seq beat-bus]
  (assert (monome-sequencer? m-seq))
  (seq/swap-beat-bus! @(:sequencer m-seq) beat-bus))

(defn sequencer-write! [{fonome :fonome sequencer :sequencer} y pattern]
  (let [stretched-pattern (take (:width fonome) (cycle pattern))]
    (fon/set-led-row-state! fonome y stretched-pattern)))
