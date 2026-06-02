export class ApiError extends Error {
  constructor(
    public readonly code:
      | "BAD_REQUEST"
      | "NOT_FOUND"
      | "PROVIDER_UNAVAILABLE"
      | "NO_PLAYABLE_STREAM",
    message: string,
    public readonly statusCode: number,
    public readonly providerError?: unknown,
  ) {
    super(message);
  }
}
