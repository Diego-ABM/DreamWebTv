---
name: "feature-full"
description: "Flujo completo Spec Kit de punta a punta: especificar, clarificar, planear, generar tareas, analizar e implementar una feature con un solo comando."
---


## User Input

```text
$ARGUMENTS
```

Los argumentos son la descripción en lenguaje natural de la feature a construir. Si están vacíos, pregunta al usuario qué feature quiere construir antes de continuar.

## Modelo de ejecución

Esta skill es un **orquestador**. Tú (el agente principal) no ejecutas el trabajo pesado: lo delegas en subagentes para mantener tu contexto limpio.

- Los pasos marcados como **[subagente]** se ejecutan con la herramienta `run_subagent` (perfil `subagent_general`, `is_background: false`). Cada subagente corre con contexto limpio, ejecuta UNA skill de Spec Kit y reporta el resultado.
- Los pasos marcados como **[principal]** los ejecutas tú directamente, porque son interactivos (requieren preguntarle al usuario).
- Las skills speckit **no se modifican**: el subagente las ejecuta inline dentro de su propio contexto.

### Plantilla de tarea para cada subagente

Al lanzar un subagente, dale una tarea autocontenida (no ve tu conversación):

```
Lee y sigue las instrucciones de la skill `/speckit-<nombre>`
(archivo: .devin/skills/speckit-<nombre>/SKILL.md) con esta entrada:

<descripción de la feature o directorio del spec, p. ej. specs/003-descuentos/>

Al terminar reporta: qué archivos creó o modificó, decisiones clave que tomó,
advertencias o preguntas abiertas que requieran input del usuario.
```

## Flujo

Ejecuta los siguientes pasos **en orden**, esperando el resultado de cada subagente antes de lanzar el siguiente. No te saltes pasos ni cambies el orden.

### Paso 1 — Especificación [subagente]

Lanza un subagente que ejecute `/speckit-specify` con la descripción de la feature. Este paso solo genera `spec.md`; no escribe código. Guarda la ruta del directorio `specs/<NNN>-<nombre>/` que reporte — la necesitan todos los pasos siguientes.

### Paso 2 — Clarificación [principal]

Invoca `/speckit-clarify` tú mismo. Esta skill identifica áreas ambiguas del spec y hace preguntas al usuario. **Deja que haga sus preguntas y espera las respuestas** antes de continuar.

### Paso 3 — Plan y tareas [subagente]

1. Lanza un subagente que ejecute `/speckit-plan` sobre el spec del Paso 1. Si reporta dudas o decisiones abiertas, pregúntale al usuario con `ask_user_question`; si las respuestas cambian el plan, lanza un subagente breve que las aplique a `plan.md`.
2. Lanza otro subagente que ejecute `/speckit-tasks` sobre el mismo spec.

### Paso 4 — Análisis [subagente]

Lanza un subagente que ejecute `/speckit-analyze`. Muestra al usuario un resumen breve de los hallazgos que reporte (inconsistencias, huecos, riesgos).

### Paso 5 — Confirmación antes de implementar [principal]

**DETENTE aquí.** Pregunta al usuario con `ask_user_question`:

- ¿Proceder con la implementación (`/speckit-implement`)?
- Opciones: "Sí, implementar" / "No, revisar primero" / "Solo hasta cierta tarea"

Si el usuario elige no implementar, termina el flujo aquí y dile que puede retomar con `/speckit-implement` cuando quiera.

### Paso 6 — Implementación [subagente]

Lanza un subagente que ejecute `/speckit-implement` sobre el spec. Si es un trabajo muy largo, puede lanzarse con `is_background: true` y reportar el avance al terminar.

## Reglas de comportamiento

- **Pregunta, no asumas**: si en cualquier paso falta información crítica o hay ambigüedad que bloquee el avance, usa `ask_user_question` en vez de decidir por tu cuenta. Los subagentes no pueden preguntarle al usuario: si reportan preguntas abiertas, se las relayas tú.
- **No interrumpas de más**: si todo está claro, avanza al siguiente paso sin pedir confirmación. Los únicos puntos de pausa obligatorios son las preguntas de `/speckit-clarify` y la confirmación del Paso 5.
- **Contexto entre pasos**: cada subagente arranca limpio. Pásale siempre la ruta del spec (`specs/<NNN>-<nombre>/`) y cualquier respuesta del usuario que necesite.
- **Errores**: si un paso falla (el subagente falla, el build no compila, etc.), repórtalo al usuario y detén el flujo; no intentes continuar al siguiente paso.
- **Resumen final**: al terminar (o al detenerte), reporta en qué paso quedó el flujo y qué archivos se generaron (`specs/<NNN>-<nombre>/...`).
