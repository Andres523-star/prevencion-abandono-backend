package co.edu.retencion.common.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Convertidor JPA que cifra/descifra columnas de texto de forma transparente:
 * {@code @Convert(converter = EncryptedStringConverter.class) private String email;}
 *
 * <p>Hibernate lo crea a través del contenedor de beans de Spring (Spring Boot configura
 * {@code SpringBeanContainer}), por eso puede recibir el {@link EnvelopeEncryptor} por constructor.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final EnvelopeEncryptor encryptor;

    public EncryptedStringConverter(EnvelopeEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return encryptor.decrypt(dbData);
    }
}
