# Identity Module

`identity` is reserved for account, authentication, role, and permission related business code.

This scaffold keeps the module intentionally empty so projects can choose their own identity model without carrying demo tables or opinionated login flows.

Recommended structure when the module is implemented:

```text
modules/identity/src/main/java/com/zkry/identity
├── domain
├── mapper
├── service
└── service/impl
```

Keep HTTP controllers in `app/src/main/java/com/zkry/api/user` or `app/src/main/java/com/zkry/api/admin`; this module should expose business services only.
