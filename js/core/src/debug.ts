export function createDebugLogger(enabled = false) {
  return (
    message: string,
    details?: Record<string, number | boolean | undefined>,
  ) => {
    if (!enabled) return;
    try {
      if (details) console.debug(`[Flagbearer] ${message}`, details);
      else console.debug(`[Flagbearer] ${message}`);
    } catch {
      // Diagnostics must never interrupt flag updates.
    }
  };
}
