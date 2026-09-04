package com.alphaadopter.core.pipeline

import org.springframework.data.mongodb.repository.MongoRepository

interface RawNewsMongoRepository : MongoRepository<RawNewsDocument, String>
