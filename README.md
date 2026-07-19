# Agent Le Man

A real gentleman. Interface to a Hermes Agent via the OpenAI-compatible API.

## Firebase setup

Push notifications need `app/google-services.json`, which is **gitignored** — a
fresh clone (and CI) will not have it, and the build will fail without it.

Download it from the Firebase console for this project. The config must register
**both** Android apps, or one of the two build variants ends up with no Firebase
config at all:

- `net.liquidx.leman` (release)
- `net.liquidx.leman.debug` (debug)

Drop the file at `app/google-services.json`. Do not commit it.
