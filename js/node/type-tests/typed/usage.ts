import { init } from "../../src/index";

const client = init({ key: "fbs_test" });
const user = { key: "user-1" };
void user;

client.isEnabled("new-checkout", user);
// @ts-expect-error: not a flag in this app
client.isEnabled("new-chekout", user);

const flags = client.allFlags(user);
const known: boolean | undefined = flags["dark-mode"];
// @ts-expect-error: not a flag in this app
void flags["dark-mod"];
void known;
