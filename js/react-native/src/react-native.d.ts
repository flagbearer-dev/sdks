// The one React Native API this SDK uses, so building it doesn't need all of
// React Native installed.
declare module "react-native" {
  type AppStateStatus =
    "active" | "background" | "inactive" | "unknown" | "extension";

  export const AppState: {
    currentState: AppStateStatus | null;
    addEventListener(
      type: "change",
      listener: (state: AppStateStatus) => void,
    ): { remove(): void };
  };
}

// True in a development build. React Native defines it as a global.
declare const __DEV__: boolean | undefined;
