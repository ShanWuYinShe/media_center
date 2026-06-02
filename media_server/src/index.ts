import { buildServer } from "./server.js";

const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? "0.0.0.0";
const server = buildServer();

await server.listen({ host, port });
