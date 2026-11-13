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
| `/api/scheduling/monthly`     | Propose + store sparse monthly overrides (async)        | `channel`, `channel_id` |
| `/api/scheduling/quarterly`   | Generate + freeze the quarterly grid (async)            | `date` (`YYYY-MM-DD` or `next-quarter`, default today), `channel`, `channel_id` |

All tasks accept optional repeatable `channel=<key>` / `channel_id=<uuid>`
selectors to limit the run to specific channels (omit to run all configured
channels).

### Quarter transitions (`?date`)

`quarterly` (re)generates the grid for the quarter of `?date` (default today).
The grid's quarter and year come from that date — pass a date **inside the
quarter you want**, e.g. `?date=2026-10-01` to target Q4 2026.

`?date=next-quarter` targets the quarter after today's.

- Targeting the **next** quarter (what the CronJob does, on the 1st of the
  quarter's last month) freezes the grid and syncs it as slots that start on
  the quarter's first day, beside the current grid's slots, which end the day
  before. The channel switches over exactly on the boundary, and the monthly
  run on the 10th can store overrides for the new quarter's first month. It
  needs the current quarter's grid frozen. Run it more than 14 days before the
  boundary, so the switch lands past the already-built timeline.
- Targeting the **current** quarter replaces its grid and syncs it (plus the
  next quarter's, if frozen). The timeline is *extended*, not reset, so
  already-published programming plays out first.
- Targeting **any other** quarter (backfilling, or two quarters ahead) **freezes
  the grid only** and leaves the live playout untouched.

Review a pre-generated grid via `/api/scheduling/channels/:channel/grid`
before the boundary; re-run with `?date=next-quarter` to replace it.

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
  defaults: daily 03:00, monthly 10th 05:00 (for next month), quarterly
  Mar/Jun/Sep/Dec 1st 06:00 (for next quarter).

## Triggering a task manually

Run any task immediately by creating a one-off Job from a CronJob:

```sh
kubectl create job --from=cronjob/tunarr-scheduler-daily daily-adhoc -n arr
```

…or just curl the endpoint from inside the cluster:

```sh
curl -fsS -X POST \
  "http://tunarr-scheduler.arr.svc.cluster.local:5545/api/scheduling/monthly?commit=false"
```
