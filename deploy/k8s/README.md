# Kubernetes scheduling

Tunarr Scheduler does **not** run an in-process cron. Periodic programming work
is driven by Kubernetes `CronJob`s that POST to the running service's
`/api/scheduling/*` endpoints. This keeps timing in the platform that owns it,
avoids the multi-replica double-fire problem, and lets you trigger any task
on-demand with a single `curl`.

## Endpoints

All are `POST` and return `200` with a JSON summary (`{:task ...}`), or `500`
with `{:error ...}` on failure.

| Endpoint                      | What it does                                            | Query params |
|-------------------------------|---------------------------------------------------------|--------------|
| `/api/scheduling/daily`       | Extend the playout horizon for every channel            | `horizon` (days, default `14`), `channel`, `channel_id` |
| `/api/scheduling/weekly`      | Re-apply schedule templates to every channel            | `channel`, `channel_id` |
| `/api/scheduling/monthly`     | Propose + store sparse monthly overrides (async)        | `channel`, `channel_id` |
| `/api/scheduling/quarterly`   | Generate + freeze the quarterly grid (async)            | `date` (`YYYY-MM-DD`, default today), `channel`, `channel_id` |

All tasks accept optional repeatable `channel=<key>` / `channel_id=<uuid>`
selectors to limit the run to specific channels (omit to run all configured
channels).

### Quarter transitions (`?date`)

`quarterly` (re)generates the grid for the quarter of `?date` (default today).
The grid's quarter and year come from that date — pass a date **inside the
quarter you want**, e.g. `?date=2026-10-01` to target Q4 2026.

- Targeting the **current** quarter freezes the grid **and** applies it: the new
  schedule is attached in Pseudovision and the timeline is *extended* (not reset)
  into it. Already-published near-term programming is preserved and the new grid
  takes over at the end of it — no hard cutover on the 1st, so the guide viewers
  saw yesterday stays correct.
- Targeting **any other** quarter (pre-generating next quarter early, or
  backfilling) **freezes the grid only** and leaves the live playout untouched.
  The native schedule has no effective-date concept, so applying a future grid
  now would air it immediately; instead it's frozen and reviewable, and applied
  by the regular current-quarter run once the boundary arrives.

So to preview/de-risk next quarter ahead of time, run e.g.
`POST /api/scheduling/quarterly?date=<first day of next quarter>` a week or two
early (freeze-only), review the outline via `/api/scheduling/channels/:channel/grid`,
then let the 1st-of-quarter CronJob apply it.

## Applying the CronJobs

`cronjobs.yaml` contains the four CronJobs. Before applying, set these to match
your cluster:

- **Service URL** — the manifests target
  `http://tunarr-scheduler.arr.svc.cluster.local:5545`. Change the host/port (and
  namespace) to your `Service`.
- **Namespace** — apply into the namespace where the service runs:
  ```sh
  kubectl apply -n arr -f deploy/k8s/cronjobs.yaml
  ```
- **Schedules / timeZone** — the defaults (UTC) mirror the previous in-process
  defaults: daily 03:00, weekly Mon 04:00, monthly 1st 05:00, quarterly
  Jan/Apr/Jul/Oct 1st 06:00.

## Triggering a task manually

Run any task immediately by creating a one-off Job from a CronJob:

```sh
kubectl create job --from=cronjob/tunarr-scheduler-weekly weekly-adhoc -n arr
```

…or just curl the endpoint from inside the cluster:

```sh
curl -fsS -X POST \
  "http://tunarr-scheduler.arr.svc.cluster.local:5545/api/scheduling/monthly?commit=false"
```
