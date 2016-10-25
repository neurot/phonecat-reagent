(defproject phonecat-reagent "0.1.0-SNAPSHOT"
  :dependencies [;; the Clojure(Script) language
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.293"]

                 ;; utils
                 [com.lucasbradstreet/cljs-uuid-utils "1.0.2"]

                 ;; client-side dependencies
                 [reagent "0.6.0"]
                 [cljs-ajax "0.5.8"]
                 [bidi "2.0.13"]
                 [org.clojure/core.async "0.2.395"]]

  :min-lein-version "2.5.3"

  :source-paths ["src/clj"]

  :plugins [[lein-cljsbuild "1.1.4"]
            [lein-cljfmt "0.5.6"]]

  :clean-targets ^{:protect false} ["resources/public/js/compiled"
                                    "target"]

  :figwheel {:css-dirs ["resources/public/css"]}


  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

  :profiles
  {:dev
   {:dependencies [[com.cemerick/piggieback "0.2.1"]
                   [figwheel-sidecar "0.5.8"]]

    :plugins      [[lein-figwheel "0.5.8"]
                   [cider/cider-nrepl "0.14.0"]]}}

  :cljsbuild
  {:builds
   [{:id           "dev"
     :source-paths ["src/cljs"]
     :figwheel     {:on-jsload "phonecat-reagent.core/reload"}
     :compiler     {:main                 phonecat-reagent.core
                    :output-to            "resources/public/js/compiled/app.js"
                    :output-dir           "resources/public/js/compiled/out"
                    :asset-path           "js/compiled/out"
                    :source-map-timestamp true}}

    {:id           "min"
     :source-paths ["src/cljs"]
     :compiler     {:main          phonecat-reagent.core
                    :output-to     "resources/public/js/compiled/app.js"
                    :optimizations :advanced
                    :closure-defines {goog.DEBUG false}
                    :pretty-print  false}}]})
