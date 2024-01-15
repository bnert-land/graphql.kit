<p align="center">
  <img style="border-radius: 8px;" src="./assets/graphql.kit.png" align="center">
</p>
<p align="center">
    GraphQL for Clojure Ring Implementations
</p>

## GraphQL "Engines"

| Name                                              | Compliant w/ GraphQL Spec | Compliant w/ Apollo Federation |
|:--------------------------------------------------|:--------------------------|:-------------------------------|
| [Lacinia](https://github.com/walmartlabs/lacinia) | yes                       | yes, only w/ SDL               |


## Web Server Features/Compliance

| Web Server | GraphQL over HTTP | [GraphQL over WebSocket (graphql-ws)](#one) | [GraphQL over Server-Sent Events](#three) |
|:-----------|:------------------|:--------------------------------------------|:------------------------------------------|
| Aleph      | yes (via Ring)    | yes                                         | no, planned                               |
| Ring       | yes               | [yes (1.11.0 spec)](#two)                   | no, planned                               |

If a server isn't listed, and it is Ring compliant, then you should be good to go.

Additionally, federation support hasn't been tested and relies on the underlying "GraphQL engine"


## Motivation

I wanted to be able to pick my web stack and use GraphQL in a fairly
intuitive manner which hooks into ring compliant servers. I want to to also
be able to do the same (if it is Ring compliant).

This README is still a todo, there is a bit to document.


## Examples

- [Aleph w/ Lacinia Engine, GraphiQL](./examples/aleph/)
- [Ring Jetty w/ Lacinia Engine, GraphiQL](./examples/ring-jetty)

Still a todo, but on the table are:

- Server only Aleph w/ ~~HTTP, WebSocket~~, and SSE when available
- Server only Ring (Jetty) w/ ~~HTTP, WebSocket,~~ and SSE when available
- Fullstack using the two above w/ Apollo Client
- TodoMVC?
- Basic Chat App?


## References

- <a id="one">[1]:</a> [GraphQL over WebSocket Spec](https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md)
- <a id="two">[2]:</a> [Ring 1.11.0 WebSocket Spec](https://github.com/ring-clojure/ring/blob/master/SPEC.md#3-websockets)
- <a id="three">[3]:</a> [GraphQL over Server-Sent Events Spec](https://github.com/enisdenjo/graphql-sse/blob/master/PROTOCOL.md)


---

Copyright (c) Brent Soles. All rights reserved.

See [LICENSE](./LICENSE) for license information.
