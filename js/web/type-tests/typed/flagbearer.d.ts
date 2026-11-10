// What `flagbearer generate types` writes, pointed at the source.
import "../../src/index";

declare module "../../src/index" {
  interface Flags {
    "dark-mode": true;
    "new-checkout": true;
  }
}
