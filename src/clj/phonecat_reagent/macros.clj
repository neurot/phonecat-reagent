(ns phonecat-reagent.macros)

;; (defmacro <?
;;   "Version of <? which throws an Error that come out of the channel."
;;   [c]
;;   '(phonecat-reagent.core/throw-err (cljs.core.asyn/<! ~c)))

;; (defmacro err-or
;;   "If body throws an exception, catch it and return nit."
;;   [& body]
;;   '(try
;;      ~@body
;;      (catch js/Error e# e#)))

;; (defmacro go-safe
;;   "'Safe' version of cljs.core.async/go which catches and returns an exception
;;   which are thrown in it's body."
;;   [& body]
;;   '(cljs.core.async.macros/go (phonecat-reagent.core/err-or ~@body)))

;; (defmacro spy
;;   [form]
;;   (let [text (str form)]
;;     '(let [r# ~form]
;;        (prn ~text r#)
;;        r#)))
