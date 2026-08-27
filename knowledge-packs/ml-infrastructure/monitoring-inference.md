# Monitoring Inference

## When to use
- Running model inference in production behind an API
- Detecting performance degradation before users notice
- Capacity planning based on actual usage patterns
- Diagnosing latency spikes and throughput drops

## Pattern

### Latency Metrics

**Time to First Token (TTFT)**:
- Measures prompt processing time (prefill phase)
- Affected by prompt length, model size, and GPU load
- Critical for interactive/streaming applications
- Target: typically under 500ms for good user experience

**Inter-Token Latency (ITL)**:
- Time between consecutive generated tokens (decode phase)
- Should be relatively constant under stable load
- Spikes indicate GPU contention or memory pressure
- Target: 20-60ms per token depending on model size

**End-to-End Latency (E2E)**:
- Total time from request received to response complete
- E2E = TTFT + (num_tokens x ITL) + overhead
- Track P50, P95, P99 percentiles (averages hide tail latency)
- Set alerts on P99, not P50

**Queue Wait Time**:
- Time a request spends waiting before inference begins
- Indicates capacity saturation when consistently above zero
- Separate from compute time for accurate diagnosis

### Throughput Metrics

- **Requests per second**: overall system throughput
- **Tokens per second (generation)**: actual model output rate
- **Tokens per second (prefill)**: prompt processing rate
- **Concurrent requests**: active in-flight requests at any point
- **Batch utilization**: average batch size vs maximum configured batch size

### Resource Metrics

- GPU utilization % (compute): should be high (>80%) under load
- GPU memory utilization %: track headroom for KV cache growth
- GPU memory allocated vs reserved: detect fragmentation
- CPU utilization: high CPU with low GPU may indicate preprocessing bottleneck
- Network I/O: relevant for distributed inference or high-throughput APIs

### Quality Degradation Detection

**Output quality monitoring**:
- Log a sample of inputs and outputs for periodic review
- Track output length distribution (sudden changes suggest issues)
- Monitor structured output parse failure rate (JSON, tool calls)
- Compare against known-good reference outputs on canary prompts

**Canary requests**:
- Periodically send known inputs with expected outputs
- Compare actual output against expected (exact match or similarity threshold)
- Alert when canary quality drops below threshold
- Run canaries from the client side to include full stack

### Alerting Strategy

| Metric | Warning | Critical |
|--------|---------|----------|
| P99 TTFT | >2x baseline | >5x baseline |
| P99 E2E | >2x baseline | >5x baseline |
| GPU memory | >85% | >95% |
| Error rate | >1% | >5% |
| Queue depth | >10 sustained | >50 sustained |
| Canary quality | <95% match | <80% match |

### Dashboard Essentials

Build a single-page dashboard with:
1. Request rate and error rate (time series)
2. Latency percentiles: P50, P95, P99 (time series)
3. GPU utilization and memory (per-device)
4. Active requests and queue depth
5. Token throughput (generation tokens/sec)
6. Model version currently serving

## Gotchas / Anti-patterns
- Monitoring only average latency (hides P99 spikes that affect real users)
- Alerting on GPU utilization being too low (low utilization is fine during off-peak)
- Not separating TTFT from generation latency (different causes, different fixes)
- Logging every request input/output without sampling (storage and privacy concerns)
- Setting static thresholds without baselining actual performance first
- Monitoring the inference server but not the load balancer or network path
- Not tracking model version in metrics (makes it impossible to correlate deployments with changes)
- Ignoring queue wait time and blaming the model for latency that is actually queueing

## References
- OpenTelemetry GenAI semantic conventions: https://opentelemetry.io/docs/specs/semconv/gen-ai/
- NVIDIA DCGM (Data Center GPU Manager): https://developer.nvidia.com/dcgm
- Prometheus GPU metrics exporter: https://github.com/NVIDIA/dcgm-exporter
- Grafana dashboard best practices: https://grafana.com/docs/grafana/latest/dashboards/
