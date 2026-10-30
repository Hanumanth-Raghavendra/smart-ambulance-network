package com.smartambulance.service;

import com.smartambulance.domain.AppSetting;
import com.smartambulance.repo.Repos.SettingRepo;
import org.springframework.stereotype.Service;

@Service
public class SettingsService {
    private static final String AUTO_APPROVE = "autoApprove";
    private final SettingRepo repo;

    public SettingsService(SettingRepo repo) {
        this.repo = repo;
    }

    /** When true, green-corridor requests are approved automatically instead of waiting for traffic police. */
    public boolean autoApprove() {
        return repo.findById(AUTO_APPROVE).map(s -> Boolean.parseBoolean(s.getValue())).orElse(false);
    }

    public void setAutoApprove(boolean v) {
        repo.save(new AppSetting(AUTO_APPROVE, String.valueOf(v)));
    }
}
