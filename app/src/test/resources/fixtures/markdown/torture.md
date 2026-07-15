# heading one

a paragraph with **strong**, *emphasis*, `inline code`, [a link](https://example.com), and ~~strikethrough~~.

- top level bullet
- another bullet
  - nested bullet
    - deeper still
- back at top

1. ordered one
2. ordered two

> a blockquote with `code` inside
> spanning two lines

```kotlin ci/pipeline.kt
fun retry(block: () -> Unit) {
    repeat(3) { block() }
}
```

```diff ci/pipeline.yml
@@ -12,7 +12,9 @@
 jobs:
-  test:
-    retries: 0
+  test:
+    retries: 2
+    timeout: 30m
```

| option | price | notes |
|--------|-------|-------|
| tgv 6:12 | 74 chf | direct · 3h 41m |
| tgv 8:47 | 49 chf | 1 change · 4h 10m |

- [x] reproduce the flake locally
- [x] bisect to the retry helper
- [~] patch backoff timing
- [ ] re-run full suite

<details><summary>fare rules · 3 conditions</summary>
non-refundable after 24h. seat reservation included. bikes need a separate pass.
</details>

***

final paragraph after a thematic break with emoji 🎉 and RTL text العربية mixed in.

```python
# an unterminated fence to torture the streaming renderer
def half_done(
