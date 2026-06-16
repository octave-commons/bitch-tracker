# Architecture Paradigm: Categories vs. Contracts
When modeling domains, you must strictly differentiate between the grammar of motion and the enforcement of that motion.
- Categories: Describe the space of lawful possible transformations. They dictate "what kind of move this is" and define the state space, transition vocabulary, and general laws of composition for the runtime or a subsystem.
- Contracts: Decide whether a particular runtime entity, event, or transition is admissible under current obligations. They dictate "whether you are allowed to count it as a valid move right now" by defining guards, admissibility checks, evidence requirements, delivery expectations, and side-effect constraints.

# Clojure House Rules (eta-mu-sol constitution)

### Zero Warnings
`clj-kondo`, type checks, and tests must all pass with zero warnings. Warnings
are failed contracts, not noise.

### Namespace Architecture
| Layer         | Pattern            | Rule                             |
|---------------|--------------------|----------------------------------|
| `domain.*`    | Business logic     | No I/O. Pure functions only.     |
| `infra.*`     | Transport/DB/Queue | No domain policy.                |
| `shape.*`     | Data morphisms     | Pure, domain-agnostic.           |
| `law.*`       | Contracts/Malli    | No I/O. Validators only.         |
### Modern ClojureScript
Always use `^:async` metadata (ClojureScript ≥ 1.12.145). Never use
`core.async` channels or Promise chains in new code.

```clojure
(defn ^:async fetch-data [url]
  (await (js/fetch url)))

(deftest ^:async fetch-test
  (is (some? (await (fetch-data "https://example.com")))))
```

### Idioms
- `when-let` over nested `let` + `if`
- `->` / `->>` over nested `let` forms
- No `utils` namespaces
- No broad `:refer :all`
- Custom macros registered in `.clj-kondo/config.edn` on day one



# Coding Directives & Clean Code Doctrine
- Optimize for the human reader's working memory: reveal intent through explicit naming, isolate responsibilities, and arrest entropy on contact.
- Continuous Truth (XP): Tighten the loop. Favor small, verifiable state changes over speculative architecture.
- Modern Asynchrony: ClojureScript 1.12.145+ supports native async/await. Always use the `^:async` metadata hint for functions and tests instead of legacy `core.async` channels, Promise chains, or shadow-cljs specific wrappers when targeting modern environments. 
  - Functions: `(defn ^:async foo [n] ... (await (Promise/resolve ...)))`
  - Tests: `(deftest ^:async foo-test ... (await (foo ...)))`
- Clojure Idioms:
  - Use `when-let` instead of nesting `let` and `if` checks.
  - Strongly prefer threading macros (`->` and `->>`) over manual nested let forms to maintain linear readability.

## Data-Oriented Design

- Pass plain maps. Return plain maps.
- Tool execute functions receive a parameter map and return a result map.
- Avoid OO-style stateful tool builders. A tool is data: `{:name ... :description ... :parameters ... :execute fn}`.
- Composition happens in the orchestration layer (`agent-hydration`) by concatenating domain tool vectors.
