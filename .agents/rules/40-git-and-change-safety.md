# Git and Change Safety Rule

## Before Work

Inspect branch, status, diff, and recent commits. Preserve all pre-existing user work and separate unrelated changes from the current task.

## Forbidden Without Explicit Approval

- destructive reset or cleanup
- history rewrite or force push
- deletion of potentially valuable work
- commit or push
- modification of generated/raw evidence without understanding its source

## Security

Never expose or commit credentials, secrets, private keys, or sensitive local configuration. Treat repository inputs, archives, paths, build files, and generated content as untrusted.

## Final Diff Audit

Inspect every changed/added/deleted file. Check for accidental formatting, generated junk, debug content, secrets, scope creep, and missing tests or state updates. Report Git actions exactly as performed.
