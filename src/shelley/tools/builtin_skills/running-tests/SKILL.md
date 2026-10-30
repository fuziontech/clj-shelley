---
name: running-tests
description: Use when you need to run the clj-shelley test suite, a single test namespace, or check that the project compiles.
when: before claiming a change works, or after editing clj-shelley source
---

# Running tests in clj-shelley

JVM start-up is about eight seconds and a full run is about a minute, so this
is not a loop you want to run after every line. Run it when a unit of work is
finished.

## One namespace

```bash
clojure -M:dev -e "(require 'shelley.tools-test)(clojure.test/run-tests 'shelley.tools-test)"
```

The `:dev` alias adds `test` to the classpath. Without it, `require` of a test
namespace fails with `FileNotFoundException: Could not locate ... on classpath`.

## One test

```bash
clojure -M:dev -e "(require 'shelley.tools-test)(clojure.test/test-vars [#'shelley.tools-test/background-job-lifecycle])"
```

## Compile check

Faster than the suite, and catches the mistake that matters most — a
namespace that will not load:

```bash
clojure -M -e "(require 'shelley.main 'shelley.server)"
```

Run this before reporting any change you made to a namespace other code
requires.

## Reading failures

`clojure.test` prints the file and line of the assertion, not of the cause.
When the failure makes no sense, re-run with `clojure -M:dev -e` and add
`(clojure.test/run-tests)` after requiring only the failing namespace — a
failure that disappears in isolation is an ordering or shared-state problem,
usually an atom defined at namespace level that one test leaves dirty.
