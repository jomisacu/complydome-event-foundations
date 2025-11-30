Aquí tienes el contenido completo formateado en Markdown, listo para que lo copies y pegues en tu archivo `HELP.md`.

-----

# ComplyDome Event Foundations

Librería base para estandarizar la comunicación por eventos entre microservicios utilizando **AWS SNS (Publicación)** y
**AWS SQS (Consumo)**.

Esta librería abstrae la infraestructura de AWS, permitiendo al equipo trabajar con eventos de dominio puros y
desacoplados sin configurar clientes de mensajería manualmente.

## 1\. Instalación

Agrega la dependencia en el `pom.xml` de tu microservicio:

```xml

<dependency>
    <groupId>com.complydome</groupId>
    <artifactId>event-foundations</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## 2\. Configuración (`application.yml`)

Dependiendo de si tu microservicio solo publica, solo consume, o hace ambas cosas, configura las siguientes propiedades
en tu archivo `application.yml`:

```yaml
app:
  events:
    # OBLIGATORIO: Dónde buscar tus clases de eventos (acepta una lista de paquetes)
    scan-packages:
      - "com.complydome.users.domain.events"

    # OPCIONAL (Solo Productores): El ARN del Topic SNS donde se publicarán los eventos
    topic-arn: "arn:aws:sns:us-east-1:123456789:complydome-domain-events"

    # OPCIONAL (Solo Consumidores): Nombre de la cola SQS que escuchará este microservicio
    # Si se omite, el microservicio NO activará el consumidor de eventos.
    queue-name: "users-service-inbox-queue"
```

## 3\. Infraestructura AWS (⚠️ Crítico)

Para que el sistema funcione correctamente, debemos configurar el enlace entre SNS y SQS asegurando que los mensajes
lleguen limpios.

### Requisito: Raw Message Delivery

La librería espera recibir el JSON limpio del evento. SNS envuelve los mensajes por defecto con metadatos, lo cual rompe
la deserialización. Debemos desactivar ese comportamiento.

**Pasos en la consola de AWS:**

1. Ve a **SNS** \> Tu Topic \> **Subscriptions**.
2. Selecciona la suscripción que apunta a tu cola SQS.
3. Haz clic en **Edit**.
4. Despliega **Subscription options** y marca la casilla **Enable raw message delivery**.
5. Guarda los cambios.

> **Nota:** Asegúrate también de que la **Access Policy** de la cola SQS permita la acción `sqs:SendMessage` desde el
> ARN del Topic SNS.

## 4\. Guía de Uso

### A. Definir un Evento (El Contrato)

Crea una clase (preferiblemente un `record`) que implemente `DomainEventInterface`.
**Regla de Oro:** Debes definir la constante pública `EVENT_NAME`. Este string es el identificador único que une al
Publicador y al Consumidor.

```java
package com.complydome.users.domain.events;

import com.complydome.event_foundations.DomainEventInterface;

import java.time.Instant;
import java.util.UUID;

public record UserCreatedEvent(String userId, String email) implements DomainEventInterface {

    // ESTE VALOR ES EL CONTRATO. Debe ser idéntico en todos los microservicios (Producer y Consumer).
    public static final String EVENT_NAME = "users.user.created";

    @Override
    public String getType() {
        return EVENT_NAME;
    }

    @Override
    public Instant getOccurredOn() {
        return Instant.now();
    }

    @Override
    public String getEventId() {
        return UUID.randomUUID().toString();
    }
}
```

### B. Publicar un Evento (Producer)

Inyecta la interfaz `DomainEventPublisherInterface`. La librería se encarga de enviarlo al SNS configurado en
`topic-arn`.

```java

@Service
public class UserRegistrationService {

    private final DomainEventPublisherInterface publisher;

    public UserRegistrationService(DomainEventPublisherInterface publisher) {
        this.publisher = publisher;
    }

    public void register(String email) {
        // ... lógica de registro ...

        var event = new UserCreatedEvent("123", email);

        // Se envía a SNS automáticamente con el atributo "Subject" configurado
        publisher.publish(event);
    }
}
```

### C. Consumir Eventos (Consumer)

No necesitas usar anotaciones de SQS (`@SqsListener`). La librería lee automáticamente la cola definida en `queue-name`
y despacha los eventos al bus interno de Spring.

Solo necesitas usar `@EventListener` estándar de Spring en tu lógica de negocio.

```java

@Service
public class WelcomeEmailService {

    // Spring ejecuta esto automáticamente cuando llega el mensaje a la cola
    @EventListener
    public void onUserCreated(UserCreatedEvent event) {
        System.out.println("Nuevo usuario detectado: " + event.email());
        // Lógica de negocio: Enviar email de bienvenida...
    }
}
```

## 5\. Arquitectura de Colas

### Modelo "Inbox" (Recomendado)

Configura una única cola para tu microservicio (ej. `sales-inbox-queue`) y define esa cola en `app.events.queue-name`.

* **Ventaja:** Todos los eventos llegan ordenados a un único punto de entrada.
* **Filtrado:** Si no quieres recibir *todos* los eventos del sistema, usa **Subscription Filter Policies** en la
  suscripción de SNS en AWS para filtrar por el atributo `Subject` (que corresponde al `EVENT_NAME`).

### Modelo "Cola Específica" (Avanzado)

Si necesitas una cola dedicada solo para un tipo de evento crítico (ej. alta prioridad) y procesarla por separado:

1. **NO** uses `app.events.queue-name` (o úsala para el tráfico normal).
2. Crea un Listener manual usando SQS estándar de Spring en tu código:

<!-- end list -->

```java

@SqsListener("cola-vip-prioridad-alta")
public void listenVip(UserCreatedEvent event) {
    // Procesamiento aislado con pools de hilos dedicados si es necesario
}
```