package com.example.batch.console.repository;

import com.example.batch.console.domain.ConsoleUserAccountEntity;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ConsoleUserAccountRepository extends Repository<ConsoleUserAccountEntity, Long> {

    @Query(
            """
            SELECT id,
                   tenant_id,
                   username,
                   display_name,
                   password_hash,
                   authorities_csv,
                   enabled
              FROM batch.console_user_account
             WHERE lower(username) = lower(:username)
             LIMIT 1
            """)
    Optional<ConsoleUserAccountEntity> findByUsernameIgnoreCase(@Param("username") String username);
}
