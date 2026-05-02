--
-- Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
-- the License. You may obtain a copy of the License at
--
--      http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
-- an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
-- specific language governing permissions and limitations under the License.
--
-- Copyright 2019-2022 the original author or authors.
--

CREATE TABLE IF NOT EXISTS book (
    id BIGINT PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    author VARCHAR(255) NOT NULL
);

MERGE INTO book KEY (id) VALUES (1, 'Effective Java', 'Joshua Bloch');
MERGE INTO book KEY (id) VALUES (2, 'Java Concurrency in Practice', 'Brian Goetz');
MERGE INTO book KEY (id) VALUES (3, 'Clean Code', 'Robert Martin');
