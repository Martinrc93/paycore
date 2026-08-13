# PayCore Roadmap

Roadmap técnico-funcional para orientar el trabajo de desarrollo. No contiene fechas ni estimaciones: ordena capacidades, dependencias e invariantes que deben cumplirse antes de avanzar.

## Cómo Usarlo

- Este documento responde qué conviene construir y en qué secuencia.
- OpenSpec sigue siendo la fuente de verdad para el comportamiento de cada feature.
- Cada bloque debe convertirse en un cambio OpenSpec antes de implementar código.
- Un bloque puede dividirse en varios cambios si crece demasiado o tiene límites claros.
- Los estados indican intención de planificación, no porcentaje de avance.

## Estados

- **Completado**: implementado, verificado y respaldado por documentación o evidencia de tests.
- **Siguiente**: próxima capacidad recomendada para convertir en un cambio OpenSpec.
- **Pendiente**: dirección planificada, pero todavía no priorizada para implementación.
- **No comprometido**: idea posible o explícitamente diferida; no debe tratarse como requisito.

## Estado Actual

### Identidad y acceso — Completado

- Registro idempotente de Customers con respuesta genérica anti-enumeración.
- Provisioning durable de identidad en Keycloak mediante saga, leases, retries y reconciliación.
- Link estable de identidad externa por `(issuer, subject)`.
- Autenticación BFF con OIDC Authorization Code + PKCE.
- Sesiones server-side en PostgreSQL con cookie HTTP-only, CSRF, logout por sesión y expiración idle/absoluta.
- Revocación de sesiones cuando el Customer deja de estar activo.
- Configuración y contratos operativos de Keycloak.

Referencias: `openspec/changes/register-customer/`, `openspec/changes/authenticate-customer/`, `docs/runbooks/customer-registration.md` y `docs/runbooks/customer-authentication.md`.

### Plataforma temporal — Completado

- UTC como convención global para JVM, Jackson, Hibernate/JDBC, PostgreSQL, tests y CI.
- `Instant`/`OffsetDateTime` para instantes, `LocalDate` para fechas de negocio e inyección de `Clock` en lógica temporal.

Referencia: `openspec/specs/system/utc-timezone/spec.md` y `docs/adr/ADR-0004_ Use UTC as the System Time Zone.md`.

## Secuencia Recomendada

### 1. Ledger fundacional — Completado

**Objetivo:** convertir el ADR de doble partida en una capacidad ejecutable y auditable.

**Entregables de desarrollo:**

- Value object de dinero con `BigDecimal` y moneda explícita.
- Cuentas y tipos de cuenta con ciclo de vida definido.
- Transacción financiera inmutable con líneas debit/credit.
- Servicio de posting que rechace transacciones desbalanceadas.
- Persistencia de transacción y líneas en una única operación atómica.
- Migraciones Flyway, constraints e índices necesarios para preservar invariantes.
- Puertos de aplicación separados de entidades JPA y DTOs HTTP.
- Consulta de movimientos con orden estable y trazabilidad.

**Invariantes mínimas:**

- `SUM(DEBITS) == SUM(CREDITS)` por transacción.
- Una transacción confirmada no se actualiza ni se elimina.
- Una corrección se registra como transacción compensatoria.
- No se usan `float` ni `double` para dinero.
- La persistencia de todas las líneas es atómica.

**Pruebas mínimas:** balance, moneda, precisión, estados inválidos, rollback, inmutabilidad, compensación y concurrencia de posting.

Referencia: `docs/adr/ADR-0003_ Use Double-Entry Ledger.md`.

Implementación y evidencia: `docs/adr/ADR-0005_ Define Ledger Posting Model.md`,
`openspec/changes/ledger-foundation/`, `docs/runbooks/ledger.md` y
`docs/verification/ledger-foundation.md`.

### 2. Cuentas y saldos — Pendiente

**Objetivo:** permitir que un Customer tenga cuentas operables y consultar saldos derivados del ledger.

**Entregables de desarrollo:**

- Ownership de cuentas y asociación con Customer mediante API de aplicación.
- Estados de cuenta: creación, activación, bloqueo y cierre.
- Restricción de moneda por cuenta.
- Proyección o consulta optimizada de saldo, siempre derivable desde el ledger.
- Reconciliación entre saldo optimizado y movimientos autoritativos.
- Control de concurrencia para operaciones que compiten por el mismo saldo.

**Pruebas mínimas:** aislamiento entre Customers, estados inválidos, saldo cero, alta concurrencia, consistencia después de rollback y reconstrucción desde ledger.

### 3. Transferencias internas — Pendiente

**Objetivo:** mover fondos entre cuentas PayCore usando el ledger como única fuente de verdad.

**Entregables de desarrollo:**

- Comando idempotente de transferencia.
- Validación de cuenta origen/destino y moneda.
- Control de fondos insuficientes sin movimientos parciales.
- Estados de operación y resultado consultable.
- Idempotencia ante reintentos y ejecución concurrente.
- Reversión mediante transacción compensatoria.
- Límites configurables sin romper la atomicidad financiera.

**Pruebas mínimas:** happy path, fondos insuficientes, cuentas bloqueadas, moneda incompatible, duplicados, reintentos, concurrencia y rollback.

### 4. Payments externos — Pendiente

**Objetivo:** modelar el ciclo de vida de pagos sin mezclar llamadas externas con la transacción del ledger.

**Entregables de desarrollo:**

- Payment intent con estados explícitos.
- Separación entre autorización, captura, cancelación, expiración y refund.
- Adapter ports para PSPs y webhooks autenticados.
- Idempotencia por operación externa y protección contra eventos duplicados o fuera de orden.
- Outbox o mecanismo durable equivalente para publicar efectos después del commit financiero.
- Reconciliación de estados locales contra el PSP.
- Compensaciones para capturas, refunds o ajustes fallidos.

**Pruebas mínimas:** timeout, respuesta ambigua, webhook duplicado, webhook fuera de orden, retry, fallo después del commit y reconciliación.

### 5. Perfil, riesgo y compliance — Pendiente

**Objetivo:** completar la información regulatoria y operativa del Customer sin cargar responsabilidades en el módulo de identidad.

**Entregables de desarrollo:**

- Perfil individual y perfil de negocio.
- Estados y evidencias de KYC.
- Carga y verificación de documentos.
- Límites por Customer, cuenta y operación.
- Suspensión o bloqueo con impacto explícito en autenticación y operaciones financieras.
- Auditoría de cambios sensibles.

**Pruebas mínimas:** transiciones de estado, permisos, documentos inválidos, límites, reintentos, suspensión concurrente y retención de auditoría.

Nota: perfiles completos, KYC y verificación documental fueron diferidos explícitamente por `register-customer`; no forman parte del registro básico.

### 6. Conciliación y operación financiera — Pendiente

**Objetivo:** hacer operable el sistema cuando existan movimientos internos y externos.

**Entregables de desarrollo:**

- Jobs o comandos de conciliación repetibles.
- Detección de diferencias entre ledger, proyecciones y proveedores externos.
- Estados de excepción y cola de resolución manual.
- Métricas de operaciones pendientes, retries, leases, fallos y diferencias.
- Alertas sanitizadas sin secretos, tokens, emails de alta cardinalidad ni datos financieros innecesarios.
- Runbooks de recuperación, replay, rollback operativo y rotación de credenciales.
- Exportes de auditoría y trazabilidad por operación.

**Pruebas mínimas:** replay seguro, duplicados, datos faltantes, diferencias parciales, recuperación después de crash y no pérdida de historial.

## Reglas Transversales

- La arquitectura sigue siendo modular monolith con dependencias `infrastructure -> application -> domain`.
- El dominio no depende de Spring, JPA, HTTP, mensajería, caches ni entidades de persistencia.
- Toda modificación de esquema requiere una nueva migración Flyway.
- Las operaciones financieras externas deben ser retryables e idempotentes.
- La lógica temporal usa UTC e instantes inequívocos.
- Los tests de PostgreSQL, Flyway, locks, transacciones y adapters deben usar integración real cuando esos detalles sean parte del comportamiento.
- Todo endpoint nuevo debe definir autenticación, autorización, CSRF cuando aplique, errores sanitizados e idempotencia cuando modifique estado.

## Definición De Listo

Un bloque del roadmap está listo para considerarse completado cuando:

- Existe un cambio OpenSpec aplicado o sincronizado según corresponda.
- Las invariantes de dominio y los límites entre módulos tienen tests.
- Las migraciones y adapters tienen pruebas de integración cuando corresponde.
- Se cubren happy path, estados inválidos, rollback, concurrencia e idempotencia aplicables.
- La documentación operativa y los runbooks reflejan el comportamiento real.
- Se ejecutan los tests focalizados y la suite completa con resultados registrados.
- Hay revisión de seguridad y arquitectura sin hallazgos pendientes de severidad relevante.

## Fuera De Alcance Por Ahora

- Convertir el roadmap en un calendario o compromiso de fechas.
- Implementar microservicios antes de que los límites del monolito lo justifiquen.
- Agregar un broker para reemplazar mecanismos PostgreSQL sin una necesidad operacional concreta.
- Crear perfiles completos, KYC o documentos dentro del endpoint de registro.
- Tratar balances cacheados como fuente autoritativa frente al ledger.
- Agregar features de producto no respaldadas por un cambio OpenSpec aprobado.

## Mantenimiento

- Actualizar este archivo cuando una capacidad cambie de estado o aparezca una dependencia relevante.
- Mantener los detalles ejecutables en OpenSpec, no duplicarlos aquí.
- Cada cambio debe explicar por qué altera el orden, el alcance o el estado de una capacidad.
- Revisar el roadmap al cerrar un bloque OpenSpec y al proponer el siguiente.
