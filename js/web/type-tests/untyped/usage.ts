import { init } from "../../src/index";

const client = init({ clientKey: "fbc_test" });
const user = { key: "user-1" };
void user;

// Without a generated file, any string is a flag key.
client.isEnabled("new-checkout");
client.isEnabled("new-chekout");
const flags: Record<string, boolean | undefined> = client.allFlags();
void flags["anything"];
