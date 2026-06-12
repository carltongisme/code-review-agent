package org.example.domain.code.service;

import org.example.domain.code.domain.CodeDomain;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * @date 06-12-2026
 */
@Service
public class CodeDomainService {
    @Resource
    private CodeRepository codeRepository;

}
