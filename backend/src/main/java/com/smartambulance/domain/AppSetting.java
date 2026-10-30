package com.smartambulance.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_settings")
@Getter @Setter @NoArgsConstructor
public class AppSetting {
    @Id private String key;
    private String value;

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
