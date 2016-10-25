# phonecat-reagent

A [reagent](https://github.com/reagent-project/reagent) application designed to ... well, that part is up to you.
[Angular Phone Catalog Tutorial Application](https://github.com/angular/angular-phonecat) ported to ClojureScript.

Used Libraries:
* Reagent as interface between ClojureScript and React
* cljs-ajax for "Server calls"
* bidi for clientside routing

## Development Mode

### Start Cider from Emacs:

Put this in your Emacs config file:

```
(setq cider-cljs-lein-repl "(do (use 'figwheel-sidecar.repl-api) (start-figwheel!) (cljs-repl))")
```

Navigate to a clojurescript file and start a figwheel REPL with `cider-jack-in-clojurescript` or (`C-c M-J`)

### Run application:

```
lein clean
lein figwheel dev
```

Figwheel will automatically push cljs changes to the browser.

Wait a bit, then browse to [http://localhost:3449](http://localhost:3449).

## Production Build

```
lein clean
lein cljsbuild once min
```
