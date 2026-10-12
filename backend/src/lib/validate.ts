import { z } from "zod";
import { ApiError } from "./http";

/**
 * Parses a payload with zod and converts the first problem into a 400 that the
 * app can show verbatim. Validation is the first line of every route: nothing
 * reaches a query without having been typed and range-checked.
 */
export function parseOrThrow<T extends z.ZodTypeAny>(schema: T, payload: unknown): z.infer<T> {
  const result = schema.safeParse(payload);
  if (result.success) return result.data;
  const issue = result.error.issues[0];
  const path = issue?.path.join(".") ?? "";
  const message = issue?.message ?? "Invalid request.";
  throw ApiError.badRequest("validation_error", path ? `${path}: ${message}` : message);
}

/** Optional string that is trimmed and turned into `undefined` when blank. */
export const optionalText = (max = 200) =>
  z
    .string()
    .max(max, `Must be ${max} characters or fewer`)
    .transform((value) => value.trim())
    .refine((value) => value.length > 0, "Cannot be blank")
    .optional();

export const dayKeySchema = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, "Expected a YYYY-MM-DD day key");

export const emailSchema = z
  .string()
  .trim()
  .min(3, "Enter your email address")
  .max(254, "That email address is too long")
  .email("Enter a valid email address")
  .transform((value) => value.toLowerCase());

export const passwordSchema = z
  .string()
  .min(8, "Use at least 8 characters")
  .max(200, "That password is too long");

export const clientEventIdSchema = z
  .string()
  .min(8, "Missing client event id")
  .max(200, "Client event id is too long");

export const offsetMinutesSchema = z
  .number()
  .int("Expected whole minutes")
  .min(-840, "Offset is out of range")
  .max(840, "Offset is out of range");
