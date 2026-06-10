/**
 * Future State Designer feature barrel.
 *
 * In homepoc this page mounted at /designer under CanvasLayout
 * (frontend/src/App.tsx); substrate route wiring lands in a later chunk —
 * lazy-import {@link FutureStateDesigner} from this barrel when it does.
 */
export { FutureStateDesigner } from './FutureStateDesigner';
export { DecisionForm, type DecisionFormData } from './DecisionForm';
export { ImpactAnalysisPanel } from './ImpactAnalysisPanel';
