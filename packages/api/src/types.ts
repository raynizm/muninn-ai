export type Role = "ADMIN" | "USER";

export type ProcessingStatus = "PENDING" | "TRANSCRIBING" | "SUMMARIZING" | "COMPLETED" | "FAILED";

export interface UserView {
  id: string;
  email: string;
  displayName: string | null;
  role: Role;
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

/** What `/api/auth/callback` and `/api/auth/refresh` return. */
export interface TokenResponse extends TokenPair {
  /** Access token lifetime in seconds. */
  expiresIn: number;
  user: UserView;
}

export interface TokenStore {
  get(): Promise<TokenPair | null>;
  set(tokens: TokenPair | null): Promise<void>;
}

export interface AuthConfig {
  issuer: string;
  clientId: string;
  authorizationEndpoint: string;
}

export interface CallbackRequest {
  code: string;
  codeVerifier: string;
  redirectUri: string;
  /** Only for the first admin during setup. */
  setupToken?: string;
}

export interface Recording {
  id: string;
  title: string | null;
  createdAt: string;
  updatedAt: string;
  durationMs: number | null;
  mime: string;
  sizeBytes: number | null;
  uploadStatus: string;
  processingStatus: ProcessingStatus;
  lastError: string | null;
}

export interface TranscriptSegment {
  start: number;
  end: number;
  text: string;
}

export interface Transcript {
  text: string;
  segments: TranscriptSegment[];
}

export interface Summary {
  text: string;
  actionItems: string[];
}

export interface RecordingDetail extends Recording {
  transcript: Transcript | null;
  summary: Summary | null;
}

export interface LastTest {
  ok: boolean;
  message: string;
  at: string;
}

export interface Provider {
  id: string;
  scope: "SYSTEM" | "USER";
  name: string;
  baseUrl: string;
  enabled: boolean;
  /** The API key itself is never returned. */
  keySet: boolean;
  isLocal: boolean;
  createdAt: string;
  lastTest: LastTest | null;
}

export interface ProviderInput {
  name: string;
  baseUrl: string;
  /** Omit or null to keep the stored key when updating. */
  apiKey?: string | null;
  enabled?: boolean;
  scope?: "SYSTEM" | "USER";
}

export interface AiModel {
  id: string;
  providerId: string;
  modelIdentifier: string;
  capabilities: string[];
  enabled: boolean;
}

export interface ConnectionTestResult {
  ok: boolean;
  message: string;
}

export interface Settings {
  userOverrideEnabled: boolean;
  allowedDomains: string[];
  defaultChatModelId: string | null;
  defaultTranscriptionModelId: string | null;
  oidcConfigured: boolean;
  issuer: string | null;
  clientId: string | null;
  tenantId: string | null;
}

export type SettingsUpdate = Partial<
  Pick<Settings, "userOverrideEnabled" | "allowedDomains" | "defaultChatModelId" | "defaultTranscriptionModelId">
>;

export interface UploadOptions {
  createdAt?: string;
  durationMs?: number;
  title?: string;
}
