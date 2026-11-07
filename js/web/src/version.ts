declare const __FLAGBEARER_SDK_VERSION__: string | undefined;

// The build replaces this with package.json's version. Tests run the source
// directly, where it reads as a development build.
export const SDK_VERSION =
  typeof __FLAGBEARER_SDK_VERSION__ === "string"
    ? __FLAGBEARER_SDK_VERSION__
    : "0.0.0-dev";
