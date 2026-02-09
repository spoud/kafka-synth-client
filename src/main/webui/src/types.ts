// TypeScript interfaces for Kafka Synth Client API responses

export interface MessagePath {
  fromRack: string;
  toRack: string;
  viaBrokerRack: string;
  latestP99latency: number;
}

export interface LatencySummary {
  timestamps: number[];
  percentiles: Record<string, number[]>;
}

export interface ChartData {
  timestamp: string;
  p99: number;
  p95: number;
  p90: number;
  p80: number;
  p50: number;
}

export interface ApiError {
  message: string;
  status?: number;
}
