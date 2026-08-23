package com.workpath.backend.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workpath.backend.common.Result;
import com.workpath.backend.common.ResultCode;
import com.workpath.backend.entity.User;
import com.workpath.backend.mapper.UserMapper;
import com.workpath.backend.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            writeError(response, ResultCode.UNAUTHORIZED.getCode(), "未登录或 token 缺失");
            return false;
        }

        String token = header.substring(BEARER_PREFIX.length());
        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (ExpiredJwtException e) {
            writeError(response, ResultCode.UNAUTHORIZED.getCode(), "token 已过期，请重新登录");
            return false;
        } catch (Exception e) {
            writeError(response, ResultCode.UNAUTHORIZED.getCode(), "token 无效");
            return false;
        }

        Long userId = Long.valueOf(claims.getSubject());
        User user = userMapper.selectById(userId);
        if (user == null) {
            writeError(response, ResultCode.UNAUTHORIZED.getCode(), "用户不存在");
            return false;
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            writeError(response, ResultCode.UNAUTHORIZED.getCode(), "账号已被禁用");
            return false;
        }
        if (request.getRequestURI().startsWith("/api/admin")) {
            if (!"ADMIN".equals(user.getRole())) {
                writeError(response, ResultCode.FORBIDDEN.getCode(), ResultCode.FORBIDDEN.getMessage());
                return false;
            }
        }

        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private void writeError(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
    }

}
