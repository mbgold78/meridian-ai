You are a data extraction specialist for operational risk manual process mapping.
Given the following CSV data describing manual processes in an organization's operational
risk ecosystem, extract each process into the canonical JSON format.

## Input Data
{{input_data}}

## Output Format
Return a JSON array of manual process objects. Each object must have:
- name (string, required)
- description (string, required, 2-3 sentences describing what the process does and why)
- classification (string: SIGN_OFF, RECONCILIATION, BRIDGE, COMPENSATING_CONTROL, SHADOW_WORKFLOW, TRIBAL_KNOWLEDGE, OTHER)
- frequency (string: Daily, Weekly, Monthly, Quarterly, Ad-Hoc)
- primaryOwner (string)
- businessLine (string)
- defenseLine (string: FIRST_LINE, SECOND_LINE, THIRD_LINE, AUDIT)
- documentationQuality (string: DOCUMENTED, PARTIALLY_DOCUMENTED, UNDOCUMENTED, TRIBAL_ONLY)
- estimatedFte (number)
- riskRating (string: CRITICAL, HIGH, MEDIUM, LOW)

<!-- Deviation from homepoc (improve-as-you-go): the classification vocabulary above
     is the canonical ManualProcessClassification enum. Homepoc's prompt requested
     WORKAROUND/SHADOW_PROCESS/DATA_ENTRY, which the entity model rejects — one of the
     reasons the homepoc convert→commit chain could never have run end-to-end. The
     commit stage still alias-maps legacy values defensively. -->

Submit the extracted records by calling the emit_extracted_records tool exactly once.
