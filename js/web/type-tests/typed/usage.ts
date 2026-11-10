import { init } from "../../src/index";

const client = init({ clientKey: "fbc_test" });
const user = { key: "user-1" };
void user;

client.isEnabled("new-checkout");
// @ts-expect-error: not a flag in this app
client.isEnabled("new-chekout");

const flags = client.allFlags();
const known: boolean | undefined = flags["dark-mode"];
// @ts-expect-error: not a flag in this app
void flags["dark-mod"];
void known;
