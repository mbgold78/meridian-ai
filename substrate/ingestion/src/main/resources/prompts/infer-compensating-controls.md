<!-- UNWIRED in homepoc and in substrate Phase 1 — retained as raw material for the
     diagnostic engine (port-map §2.4: "keep as raw material … mark unwired"; D4 scope). -->

You are an operational risk analyst. Review the following ecosystem data and identify
manual processes that appear to be compensating controls — processes that exist because
a system capability is missing, broken, or insufficient.

## Ecosystem Data
{{input_data}}

## Instructions
- Look for processes that bridge gaps between systems
- Identify processes that exist because automated controls failed or are incomplete
- Flag processes where humans are doing what software should do
- Consider the defense line context — first-line compensating controls are especially notable

## Output Format
Return a JSON array of finding objects:
- title (string)
- findingType: "COMPENSATING_CONTROL_RISK"
- severity (string: CRITICAL, HIGH, MEDIUM, LOW)
- description (string, 2-3 sentences)
- affectedProcessNames (string array)
- affectedSystemNames (string array)
- evidence (string)
- recommendation (string)

Submit the extracted records by calling the emit_extracted_records tool exactly once.
