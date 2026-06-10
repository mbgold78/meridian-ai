<!-- UNWIRED in homepoc and in substrate Phase 1 — retained as raw material for the
     diagnostic engine (port-map §2.4: "keep as raw material … mark unwired"; D4 scope). -->

You are an operational risk analyst. Review the following ecosystem data and identify
shadow workflows — manual processes that appear to duplicate or parallel work that
should be (or is) handled by systems.

## Ecosystem Data
{{input_data}}

## Instructions
- Look for processes that track the same data as an existing system
- Identify parallel trackers, shadow spreadsheets, or duplicate data entry
- Flag workflows where the same information flows through both manual and automated paths
- Consider whether the shadow workflow indicates lack of trust in the system

## Output Format
Return a JSON array of finding objects:
- title (string)
- findingType: "SHADOW_WORKFLOW_DETECTED"
- severity (string: CRITICAL, HIGH, MEDIUM, LOW)
- description (string, 2-3 sentences)
- affectedProcessNames (string array)
- affectedSystemNames (string array)
- evidence (string)
- recommendation (string)

Submit the extracted records by calling the emit_extracted_records tool exactly once.
