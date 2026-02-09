import { type LatencySummary, type ChartData } from "../types";
import dayjs from "dayjs";

export function transformLatencyData(apiResponse: LatencySummary): ChartData[] {
  if (!apiResponse?.timestamps?.length || !apiResponse?.percentiles) {
    return [];
  }

  return apiResponse.timestamps.map((timestamp, index) => {
    const date = new Date(timestamp);

    return {
      timestamp: dayjs(date).format("YYYY-MM-DD HH:mm:ss"),
      p99: Math.ceil(apiResponse.percentiles["99"]?.[index] || 0),
      p95: Math.ceil(apiResponse.percentiles["95"]?.[index] || 0),
      p90: Math.ceil(apiResponse.percentiles["90"]?.[index] || 0),
      p80: Math.ceil(apiResponse.percentiles["80"]?.[index] || 0),
      p50: Math.ceil(apiResponse.percentiles["50"]?.[index] || 0),
    };
  });
}
