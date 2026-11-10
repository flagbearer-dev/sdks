import { init } from "../../src/index";

const client = init({ key: "fbs_test" });
const user = { key: "user-1" };
void user;

// Without a generated file, any string is a flag key.
client.isEnabled("new-checkout", user);
client.isEnabled("new-chekout", user);
const flags: Record<string, boolean | undefined> = client.allFlags(user);
void flags["anything"];
