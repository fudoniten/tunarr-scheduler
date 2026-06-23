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
| `/api/scheduling/daily`       | Extend the playout horizon for every channel            | `horizon` (days, default `14`) |
| `/api/scheduling/weekly`      | Re-apply schedule templates to every channel            | – |
| `/api/scheduling/monthly`     | Generate (and apply) a monthly programming strategy     | `commit` (`true`/`false`, default `true`) |
| `/api/scheduling/quarterly`   | Generate (and apply) a quarterly programming strategy   | `commit` (`true`/`false`, default `true`) |

`commit=false` stores the generated strategy as a `draft` for review instead of
applying it immediately; review and apply later via the `/api/strategies` API.

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
