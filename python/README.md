# flagbearer

Python SDK for Flagbearer. Downloads an environment's ruleset and evaluates flags
locally for each user, without making a network request for every flag check.
Requires Python 3.10 or later and has no dependencies.

```sh
pip install flagbearer
```

## Docs

Keys, syncing at startup, per-request checks, shutdown and exposures are covered
at [docs.flagbearer.dev/sdks/python](https://docs.flagbearer.dev/sdks/python).

## Development

Requires [uv](https://docs.astral.sh/uv/). From this directory:

```sh
uv run pytest
```

The tests include the shared evaluation fixtures in
[`fixtures/evaluation.json`](../fixtures/evaluation.json),
which the Node SDK also runs.

## Integration tests

Flagbearer's integration tests run this SDK against the real backend through
[`testing/servers/python-server.py`](../testing/servers/python-server.py).
