export type Clock = {
  now(): number;
  // Schedule asynchronously and return a cancellation function.
  setTimeout(callback: () => void, delayMs: number): () => void;
};

export const realClock: Clock = {
  now: () => Date.now(),
  setTimeout(callback, delayMs) {
    const timer = setTimeout(callback, delayMs);
    return () => clearTimeout(timer);
  },
};
