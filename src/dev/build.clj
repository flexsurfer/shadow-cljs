(ns build
  (:require [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.devtools.targets.browser :as browser]
            [shadow.cljs.build :as cljs]))

(defn closure-mod [cc co state]
  ;; new favorite option to play with
  ;; strips every (prn ...) call unconditionally
  (.setStripTypePrefixes co #{"cljs.core.prn"})

  ;; these don't work (neither Prefixes not Suffixes)
  #_(.setStripTypePrefixes co
      #{"cljs.core._STAR_print_fn_STAR_"
        "cljs.core._STAR_print_err_fn_STAR_"})
  )

(defn custom
  [{::comp/keys [stage mode config] :as state}]
  (-> state
      (browser/process)
      (cond->
        (= :init stage)
        (cljs/add-closure-configurator closure-mod))))
