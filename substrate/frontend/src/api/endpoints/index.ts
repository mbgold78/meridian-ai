/**
 * Endpoint barrel — re-exports EVERY module under src/api/endpoints.
 * homepoc's barrel omitted dna/evidence/intelligence/sources (consumers
 * imported those modules directly); fixed during the port so the barrel is
 * the complete surface. Keep this list in sync when adding a module.
 */
export * from './branding';
export * from './capabilities';
export * from './cascades';
export * from './chat';
export * from './contracts';
export * from './cost';
export * from './decisions';
export * from './dna';
export * from './domains';
export * from './ecosystem';
export * from './evidence';
export * from './findings';
export * from './intelligence';
export * from './organization';
export * from './processes';
export * from './sources';
export * from './systems';
export * from './transformation';
export { generate as generateAi } from './ai';
export type { DraftEntity, GenerateResponse, GenerateRequest, GenerativeSurface } from './ai';
