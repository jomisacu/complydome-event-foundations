package com.complydome.event_foundations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.awspring.cloud.sns.core.SnsTemplate;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.util.ClassUtils;
import software.amazon.awssdk.services.sns.SnsClient;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(EventScanProperties.class)
public class SqsAutoDiscoveryConfig {

    private final EventScanProperties properties;

    public SqsAutoDiscoveryConfig(EventScanProperties properties) {
        this.properties = properties;
    }

    /**
     * 0. VALIDACIÓN INICIAL (Fail-Fast)
     * Verificamos que si el usuario configuró algo, tenga las librerías necesarias.
     */
    @PostConstruct
    public void validateDependencies() {
        // Validación 1: Si configuró topic-arn, DEBE tener la librería de SNS
        if (properties.getTopicArn() != null && !properties.getTopicArn().isBlank()) {
            boolean snsLibraryPresent = ClassUtils.isPresent(
                    "io.awspring.cloud.sns.core.SnsTemplate",
                    this.getClass().getClassLoader()
            );
            if (!snsLibraryPresent) {
                throw new IllegalStateException(
                        "❌ ERROR CRÍTICO: Has configurado 'app.events.topic-arn' para publicar eventos, " +
                                "pero falta la librería 'spring-cloud-aws-starter-sns' en tu pom.xml."
                );
            }
        }

        // Validación 2: Si configuró queue-name, DEBE tener la librería de SQS
        if (properties.getQueueName() != null && !properties.getQueueName().isBlank()) {
            boolean sqsLibraryPresent = ClassUtils.isPresent(
                    "io.awspring.cloud.sqs.annotation.SqsListener",
                    this.getClass().getClassLoader()
            );
            if (!sqsLibraryPresent) {
                throw new IllegalStateException(
                        "❌ ERROR CRÍTICO: Has configurado 'app.events.queue-name' para consumir eventos, " +
                                "pero falta la librería 'spring-cloud-aws-starter-sqs' en tu pom.xml."
                );
            }
        }
    }

    /**
     * 1. EL CEREBRO: Módulo de Jackson
     * Escanea paquetes y registra subtipos. Si falla, detiene la aplicación.
     */
    @Bean
    public SimpleModule domainEventsModule() {
        SimpleModule module = new SimpleModule("DomainEventsModule");

        List<String> packagesToScan = properties.getScanPackages();
        if (packagesToScan == null || packagesToScan.isEmpty()) {
            throw new IllegalStateException("❌ ERROR DE CONFIGURACIÓN: La propiedad 'app.events.scan-packages' es obligatoria y no puede estar vacía.");
        }

        System.out.println(">>> [EventLib] Iniciando escaneo de eventos en: " + packagesToScan);

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(DomainEventInterface.class));

        int count = 0;
        for (String basePackage : packagesToScan) {
            for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    if (clazz.isInterface()) continue;

                    if (!clazz.isRecord()) {
                        throw new IllegalStateException(
                                "❌ ERROR DE ARQUITECTURA: El evento '" + clazz.getSimpleName() + "' es una Clase normal.\n" +
                                        "REGLA: Todos los eventos de dominio DEBEN implementarse como 'public record' para garantizar su inmutabilidad."
                        );
                    }

                    RecordComponent[] components = clazz.getRecordComponents();
                    int totalComponents = components.length;

                    if (totalComponents < 2) {
                        throw new IllegalStateException(
                                "❌ VIOLACIÓN DE ARQUITECTURA: El record '" + clazz.getSimpleName() + "' tiene muy pocos argumentos.\n" +
                                        "-> Mínimo requerido: (..., String eventId, Instant occurredOn)"
                        );
                    }

                    RecordComponent secondToLast = components[totalComponents - 2];
                    if (!secondToLast.getName().equals("eventId") || !secondToLast.getType().equals(String.class)) {
                        throw new IllegalStateException(
                                "❌ VIOLACIÓN DE ARQUITECTURA en '" + clazz.getSimpleName() + "':\n" +
                                        "-> El PENÚLTIMO parámetro debe ser exactamente: 'String eventId'.\n" +
                                        "-> Encontrado: '" + secondToLast.getType().getSimpleName() + " " + secondToLast.getName() + "'"
                        );
                    }

                    RecordComponent last = components[totalComponents - 1];
                    if (!last.getName().equals("occurredOn") || !last.getType().equals(Instant.class)) {
                        throw new IllegalStateException(
                                "❌ VIOLACIÓN DE ARQUITECTURA en '" + clazz.getSimpleName() + "':\n" +
                                        "-> El ÚLTIMO parámetro debe ser exactamente: 'Instant occurredOn'.\n" +
                                        "-> Encontrado: '" + last.getType().getSimpleName() + " " + last.getName() + "'"
                        );
                    }

                    Field typeField = clazz.getDeclaredField("TYPE");
                    String typeValue = (String) typeField.get(null);

                    if (typeValue == null || typeValue.isBlank()) {
                        throw new IllegalStateException("El campo TYPE está vacío");
                    }

                    module.registerSubtypes(new NamedType(clazz, typeValue));

                    System.out.println(">>> [EventLib] Registrado: " + typeValue + " -> " + clazz.getSimpleName());
                    count++;

                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException(
                            "❌ ERROR DE CONTRATO: La clase '" + bd.getBeanClassName() + "' implementa DomainEventInterface " +
                                    "pero le falta la constante obligatoria: 'public static final String TYPE = ...';"
                    );
                } catch (Exception e) {
                    throw new RuntimeException(
                            "❌ ERROR CRÍTICO al mapear el evento: " + bd.getBeanClassName() + ". Razón: " + e.getMessage(), e
                    );
                }
            }
        }

        if (count == 0) {
            throw new IllegalStateException("❌ ALERTA: No se encontraron eventos en los paquetes configurados: " + packagesToScan);
        }

        return module;
    }

    /**
     * 2. Consumer Converter (SQS)
     * Usa el ObjectMapper global (que ya tiene el módulo inyectado).
     */
    @Bean
    @ConditionalOnClass(SqsMessagingMessageConverter.class)
    @ConditionalOnMissingBean
    public SqsMessagingMessageConverter sqsMessagingMessageConverter(ObjectMapper objectMapper) {

        MappingJackson2MessageConverter jacksonConverter = new MappingJackson2MessageConverter();
        jacksonConverter.setObjectMapper(objectMapper);
        jacksonConverter.setSerializedPayloadClass(String.class);
        jacksonConverter.setStrictContentTypeMatch(false);

        SqsMessagingMessageConverter sqsConverter = new SqsMessagingMessageConverter();
        sqsConverter.setPayloadMessageConverter(jacksonConverter);

        return sqsConverter;
    }

    /**
     * 3. Producer Template (SNS)
     * Solo se crea si existe la librería SNS en el classpath.
     */
    @Bean
    @ConditionalOnClass(SnsTemplate.class)
    @ConditionalOnMissingBean
    public SnsTemplate snsTemplate(SnsClient snsClient, ObjectMapper objectMapper) {

        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        converter.setSerializedPayloadClass(String.class);

        return new SnsTemplate(snsClient, converter);
    }

    /**
     * 4. Publisher Wrapper (Nuestra interfaz)
     * Solo se crea si hay topic-arn configurado Y existe la librería SNS.
     */
    @Bean
    @ConditionalOnClass(SnsTemplate.class)
    @ConditionalOnProperty(name = "app.events.topic-arn")
    public DomainEventPublisherInterface domainEventPublisher(
            SnsTemplate snsTemplate) {
        return new DomainEventPublisherSNS(snsTemplate, properties.getTopicArn());
    }

    /**
     * 5. Consumer Wrapper (Nuestro listener)
     * Solo se crea si hay queue-name configurado Y existe la librería SQS.
     */
    @Bean
    // Usamos el nombre completo de la clase en String para evitar error de carga si la lib no está
    @ConditionalOnClass(name = "io.awspring.cloud.sqs.annotation.SqsListener")
    @ConditionalOnProperty(name = "app.events.queue-name")
    public DomainEventConsumerSQS domainEventConsumerSQS(ApplicationEventPublisher publisher) {
        return new DomainEventConsumerSQS(publisher);
    }
}