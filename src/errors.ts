export class ApiError extends Error {
  constructor(
    public readonly code:
      | "BAD_REQUEST"
      | "NOT_FOUND"
      | "UPSTREAM_TIMEOUT"
      | "UPSTREAM_UNAVAILABLE"
      | "NO_PLAYABLE_STREAM",
    message: string,
    public readonly statusCode: number,
    public readonly upstreamError?: unknown,
  ) {
    super(message);
  }
}
