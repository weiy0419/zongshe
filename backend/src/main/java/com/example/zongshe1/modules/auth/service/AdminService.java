package com.example.zongshe1.modules.auth.service;

import com.example.zongshe1.modules.auth.dto.api.AdminLoginRequest;
import com.example.zongshe1.modules.auth.dto.api.AdminLoginResponse;

import java.util.Map;

public interface AdminService {

    AdminLoginResponse login(AdminLoginRequest request);

    boolean validateToken(String token);

    Map<String, Object> getAdminFromToken(String token);

    Map<String, Object> getAdminInfo(String token);

    void logout(String token);
}