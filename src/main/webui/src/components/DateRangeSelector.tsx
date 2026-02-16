import { useState, useEffect } from "react";
import { useSearchParams } from "react-router";
import { Group, Button } from "@mantine/core";
import { DateTimePicker, type DateValue } from "@mantine/dates";
import dayjs from "dayjs";

export function DateRangeSelector() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [startDate, setStartDate] = useState<DateValue | null>(null);
  const [endDate, setEndDate] = useState<DateValue | null>(null);

  // Initialize from URL params
  useEffect(() => {
    const intervalStart = searchParams.get("interval_start");
    const intervalEnd = searchParams.get("interval_end");

    if (intervalStart) {
      setStartDate(new Date(intervalStart));
    }
    if (intervalEnd) {
      setEndDate(new Date(intervalEnd));
    }
  }, []);

  const handleApply = () => {
    const newParams = new URLSearchParams(searchParams);

    if (startDate) {
      newParams.set("interval_start", dayjs(startDate).toISOString());
    } else {
      newParams.delete("interval_start");
    }

    if (endDate) {
      newParams.set("interval_end", dayjs(endDate).toISOString());
    } else {
      newParams.delete("interval_end");
    }

    setSearchParams(newParams);
  };

  const handleClear = () => {
    setStartDate(null);
    setEndDate(null);
    const newParams = new URLSearchParams(searchParams);
    newParams.delete("interval_start");
    newParams.delete("interval_end");
    setSearchParams(newParams);
  };

  return (
    <Group gap="sm" align="end">
      <DateTimePicker
        label="Start Date"
        placeholder="Select start date"
        value={startDate}
        onChange={setStartDate}
        clearable
        style={{ flex: 1 }}
      />
      <DateTimePicker
        label="End Date"
        placeholder="Select end date"
        value={endDate}
        onChange={setEndDate}
        clearable
        style={{ flex: 1 }}
      />
      <Button onClick={handleApply} variant="filled">
        Apply
      </Button>
      <Button onClick={handleClear} variant="outline" color="red">
        Clear
      </Button>
    </Group>
  );
}
