# (Scratchpad) Spec

This specification serves as a reference for a "GraphQL Abstraction Layer"
for Clojure web stacks.


Some experimentation needs to be done, so this spec is pretty much empty.

Once there is a more concrete direction with respect to the abstraction, this
spec will be filled.

Some considerations:
- via HTTP, handlers to executors should conform to [ring http handler spec](https://github.com/ring-clojure/ring/blob/master/SPEC.md#1-synchronous-api).
- Any necessary functionality middleware/interceptor pattern should be "hoisted down" into the handler function. A query/mutation/subscription shouldn't need to expose its runtime via interceptors or middleware to the web stack.
  - Maybe not? A protocol could be defined which returns middleware or interceptor chain...
- via Websockets is a bit of a tough nut to crack, given there isn't a homogenous API (unlike ring handlers).
  - Juxtapose [Aleph](https://github.com/clj-commons/aleph/blob/master/examples/src/aleph/examples/websocket.clj), [Jetty (via ring)](https://github.com/ring-clojure/ring/blob/master/SPEC.md#32-websocket-listeners), [undertow](https://github.com/strojure/undertow/blob/default/doc/usage/handler_configuration.clj#L14), you'll see...
- [Lacinia](https://github.com/walmartlabs/lacinia) seems to be the only player in town at the time of writing, given the GraphQL spec has evolved and the other listed implmentations ([alumbra](https://github.com/alumbra/alumbra), [graphql-clj](https://github.com/tendant/graphql-clj)) give the impression they're not actively maintained, whereas, Lacinia gives the impression of active maintenance.
- Should work w/ clients: Apollo, re-graph, others...?

## Specs

- GraphQL Over HTTP: https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md
- GraphQL SSE: https://github.com/enisdenjo/graphql-sse/blob/master/PROTOCOL.md
- GraphQL Over Websocket: https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md

## Rough Ideas

### Protocols
This may be too lacinia specific...
```clojure
(defprotocol SchemaCompiler
  (load [this path-or-resource])
  (compile [this schema]))

(defprotocol GraphQLExecutor
  (parse [this query])
  (exec [this parsed-query variables]))

(defprotocol RingHandler
  (handle-sync [this req])
  (handle-async [this req res raise]))
```

### Map
```clojure
; returns a ring handler
(graphql.kit/http {:schema {:opts     { #_schema-compiler-options }
                            :resource "app/graphql/schema.edn" ; load a resource
                            #_#_:path     "resources/app/graphql/schema.edn" ; loads a file
                            :loader   (comp clojure.edn/read-string slurp) #_aero.core/read-config
                            :compiler com.walmartlabs.lacinia.schema/compile}
                   :resolvers {:queries       {:field-name some.ns/some-fn}
                               :subscriptions {:field-name some.ns/other-fn}}})

; returns a ws handler
(graphql.kit/ws {#_same-as-http
                 :process graphql.kit/manifold}) ; not sure about this... but may be helpful to define "presets" w/ some hooks in order to upgrade to a websocket connection, create an execution/response pipeline for the underlying messaging mechanism.

; creates a context to to have http alongside ws w/o the need for double
; compilation. Small optimization to help w/ startup time + no need to duplicate
; configuration.
;
; Using reitit syntax here...
(graphql.kit/context { #_same-as-http }
  [["/graphql"    {:get {:handler (graphql.kit/http)}
                   :post {:handler (graphql.kit/http)}}]
   ["/graphql-ws" {:get {:handler (graphql.kit/ws)}}]])
```

### Dependency Inclusion

```clojure
{:paths ["src"]
 :deps  {land.bnert/graphql.kit {:mvn/version "x.y.z"}
         land.bnert/graphql.kit.aleph {:mvn/version "a.b.c"}
         land.bnert/graphql.kit.ring-jetty {:mvn/version "b.c.e"}
         land.bnert/graphql.kit.undertow {:mvn/version "c.d.e"}}}
```

### ResolverResult conversions (automatically convert?)
- Manifold
- Core.async


### Websocket connection steps (via lacinia pedestal)

1. Connect to websocket (duh)
2. Lacinia launches 3 CSP's which are scoped to a single client.
  a. Response encoding
  b. Parsing Websocket messages and forwarding to resolvers
  c. Manging the connection and queries/mutations/subscriptions.
3. CSP "c" also manages timeouts, fault etc...


