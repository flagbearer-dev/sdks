# @flagbearer/react-native

React Native SDK for Flagbearer.

## Install

```sh
npm install @flagbearer/react-native
```

## Use

```ts
import AsyncStorage from "@react-native-async-storage/async-storage";
import { init } from "@flagbearer/react-native";

export const flags = init({ clientKey: "fbc_...", storage: AsyncStorage });

await flags.identify({ key: user.id, email: user.email });
if (flags.isEnabled("new-checkout", false)) {
  // …
}
```

`storage` is optional. With it, the app starts with the flags each user last
had, even offline. Any store with `getItem`, `setItem` and `removeItem` works.

## Develop

The client is shared with `@flagbearer/web` through the internal
`@flagbearer/sdk-core` package. This SDK supplies the React Native pieces:
`AppState` for foreground and background, and a reader for the server's stream
of flag changes. React Native has no `EventSource`, so `src/stream.ts` reads the
stream through `XMLHttpRequest`. Without a stream, flag changes arrive by
polling, about every 30 seconds.

From the repository root:

```sh
bun install
bun run --filter @flagbearer/react-native build
bun run --filter @flagbearer/react-native typecheck
bun run --filter @flagbearer/react-native test
```
